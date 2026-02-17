package com.itsaky.androidide.services.builder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * @author Akash Yadav
 */
object CpuInfo {
	private val logger = LoggerFactory.getLogger(CpuInfo::class.java)

	/**
	 * Get the CPU topology of the device.
	 *
	 * Strategy (in priority order):
	 * 1. Parse `/sys/devices/system/cpu/cpufreq/` policy directories – each
	 *    policy directory lists exactly the sibling CPUs that share a
	 *    frequency domain, which maps 1:1 to a core cluster on heterogeneous
	 *    (big.LITTLE / DynamIQ) SoCs. The directories are sorted by their
	 *    maximum frequency so that the "slowest" cluster becomes small cores
	 *    and the "fastest" becomes prime or big cores.
	 * 2. Fall back to
	 *    `/sys/devices/system/cpu/cpu<N>/cpufreq/cpuinfo_max_freq` – group
	 *    individual cores by their advertised maximum frequency.
	 * 3. Last resort: assume all cores are "big" cores (symmetric topology).
	 *
	 * @return The CPU topology.
	 */
	suspend fun getCpuTopology(): CpuTopology =
		withContext(Dispatchers.IO) {
			val total = Runtime.getRuntime().availableProcessors()

			// --- Strategy 1: cpufreq policy directories ---
			runCatching {
				val policyDir = File("/sys/devices/system/cpu/cpufreq")
				if (policyDir.isDirectory) {
					val clusters =
						policyDir
							.listFiles { f -> f.isDirectory && f.name.startsWith("policy") }
							?.mapNotNull { policy ->
								val cpuCount =
									File(policy, "related_cpus")
										.readText()
										.trim()
										.split(Regex("\\s+"))
										.count { it.isNotEmpty() }
										.takeIf { it > 0 } ?: return@mapNotNull null

								val maxFreq =
									File(policy, "cpuinfo_max_freq")
										.readText()
										.trim()
										.toLongOrNull() ?: 0L

								Pair(maxFreq, cpuCount)
							}?.sortedBy { it.first }

					if (!clusters.isNullOrEmpty()) {
						return@withContext buildTopologyFromClusters(clusters, total)
					}
				}
			}.onFailure { err ->
				logger.warn(
					"Unable to read CPU topology from policy directories. " +
						"Falling back to per-cpu max-freq grouping: {}",
					err.message,
				)
			}

			// --- Strategy 2: per-cpu max-freq grouping ---
			runCatching {
				val cpuDir = File("/sys/devices/system/cpu")
				if (cpuDir.isDirectory) {
					val freqGroups =
						(0 until total)
							.mapNotNull { idx ->
								File(cpuDir, "cpu$idx/cpufreq/cpuinfo_max_freq")
									.takeIf { it.exists() }
									?.readText()
									?.trim()
									?.toLongOrNull()
									?.let { freq -> Pair(idx, freq) }
							}.groupBy { it.second } // key = max freq
							.entries
							.sortedBy { it.key } // ascending
							.map { entry ->
								val count = entry.value.size
								Pair(entry.key, count)
							}

					if (freqGroups.isNotEmpty()) {
						return@withContext buildTopologyFromClusters(freqGroups, total)
					}
				}
			}.onFailure { err ->
				logger.warn(
					"Unable to read CPU topology from per-cpu max-freq grouping. " +
						"Falling back to 'bigCores = total': {}",
					err.message,
				)
			}

			// --- Strategy 3: symmetric fallback ---
			CpuTopology(
				primeCores = null,
				bigCores = total,
				smallCores = 0,
				totalCores = total,
			)
		}

	/**
	 * Convert an ordered list of (maxFreqHz, coreCount) pairs – sorted
	 * ascending by frequency – into a [CpuTopology].
	 *
	 * Cluster assignment:
	 * - 1 cluster → all are "big"
	 * - 2 clusters → lower = small, higher = big
	 * - 3 clusters → lowest = small, middle = big, highest = prime
	 * - 4+ clusters → lowest = small, highest = prime, everything in between =
	 *   big
	 */
	private fun buildTopologyFromClusters(
		clusters: List<Pair<Long, Int>>,
		total: Int,
	): CpuTopology =
		when (clusters.size) {
			0 -> CpuTopology(null, total, 0, total)

			1 -> CpuTopology(null, clusters[0].second, 0, total)

			2 ->
				CpuTopology(
					primeCores = null,
					bigCores = clusters[1].second,
					smallCores = clusters[0].second,
					totalCores = total,
				)

			3 ->
				CpuTopology(
					primeCores = clusters[2].second,
					bigCores = clusters[1].second,
					smallCores = clusters[0].second,
					totalCores = total,
				)

			else -> {
				// 4+ clusters: small = lowest, prime = highest, big = everything in between
				// TODO(itsaky): See if we should handle 4+ clusters differently.
				//               Can we tune Gradle parameters differently based
				//               based whether the CPU has 4+ clusters?
				val small = clusters.first().second
				val prime = clusters.last().second
				val big = clusters.drop(1).dropLast(1).sumOf { it.second }
				CpuTopology(
					primeCores = prime,
					bigCores = big,
					smallCores = small,
					totalCores = total,
				)
			}
		}
}
