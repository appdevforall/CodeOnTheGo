/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.memprof

import android.os.Debug
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samples ART's cumulative allocation counters to logcat on a fixed interval.
 *
 * Unlike `dumpsys meminfo`, which forces a full GC on every call and so erases the transient
 * garbage a peak-heap investigation exists to measure, every counter read here is free of any GC
 * side effect. `art.gc.bytes-allocated` is monotonic for the life of the process, so the difference
 * between two samples is exactly what was allocated between them.
 */
object AllocationSampler {
	private val logger = LoggerFactory.getLogger(AllocationSampler::class.java)

	/** Fine enough to separate a completion request from its neighbours, cheap enough to leave on. */
	const val DEFAULT_INTERVAL_MS = 250L

	private val running = AtomicBoolean(false)
	private val dumped = AtomicBoolean(false)

	@Volatile
	private var worker: Thread? = null

	/**
	 * Heap-in-use fraction at which a one-shot hprof is written, or 0 to never write one.
	 *
	 * Deliberately short of the ceiling: `dumpHprofData` needs to allocate, and a dump started with
	 * a few MB of headroom left dies of the OOM it was meant to explain.
	 */
	@Volatile
	var dumpAtFraction: Double = 0.0

	/**
	 * Directory the hprof is written to, set from the app context at startup.
	 *
	 * It has to be a directory this uid owns: `/data/local/tmp` belongs to shell, and a dump aimed
	 * there fails with `Permission denied` only once the heap is already at the threshold.
	 */
	@Volatile
	var dumpDir: String = "/data/local/tmp"

	/** Called on the sampler thread with the heap in use after every tick. */
	@Volatile
	var onTick: ((usedBytes: Long) -> Unit)? = null

	/** Starts sampling every [intervalMs] milliseconds. A second call while running is ignored. */
	@JvmOverloads
	fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
		if (!running.compareAndSet(false, true)) {
			logger.info("MEMPROF|sampler|{}|state=already_running", System.currentTimeMillis())
			return
		}

		val thread = Thread({ loop(intervalMs) }, "memprof-alloc-sampler")
		thread.isDaemon = true
		thread.priority = Thread.MAX_PRIORITY
		worker = thread
		thread.start()
		logger.info("MEMPROF|sampler|{}|state=started|intervalMs={}", System.currentTimeMillis(), intervalMs)
	}

	/** Stops sampling. Safe to call when not running. */
	fun stop() {
		if (!running.compareAndSet(true, false)) {
			return
		}
		worker?.interrupt()
		worker = null
		logger.info("MEMPROF|sampler|{}|state=stopped", System.currentTimeMillis())
	}

	/** Emits one sample immediately, tagged with [reason]. */
	fun sampleNow(reason: String) {
		logger.info(sample(reason, RuntimeHeapCounters.read()))
	}

	/** Emits a named marker plus a sample, so a phase boundary and its counters share a timestamp. */
	fun mark(name: String) {
		logger.info("MEMPROF|mark|{}|name={}", System.currentTimeMillis(), name)
		sampleNow(name)
	}

	private fun loop(intervalMs: Long) {
		while (running.get()) {
			val counters = RuntimeHeapCounters.read()
			// DEBUG, not INFO: profileable inherits release's Sentry setup, which turns an INFO
			// line into a breadcrumb, and this fires up to 4 times a second.
			logger.debug(sample("tick", counters))
			onTick?.invoke(counters.usedBytes)
			dumpIfAboveThreshold()
			try {
				Thread.sleep(intervalMs)
			} catch (interrupted: InterruptedException) {
				Thread.currentThread().interrupt()
				return
			}
		}
	}

	/**
	 * Writes one hprof the first time heap in use crosses [dumpAtFraction] of the growth limit.
	 *
	 * A dump taken here shows what is live while the heap is near its peak, which is the state a
	 * dump taken at idle cannot reach.
	 */
	private fun dumpIfAboveThreshold() {
		val threshold = dumpAtFraction
		if (threshold <= 0.0 || dumped.get()) {
			return
		}

		val runtime = Runtime.getRuntime()
		val used = runtime.totalMemory() - runtime.freeMemory()
		if (used.toDouble() / runtime.maxMemory() < threshold) {
			return
		}

		if (!dumped.compareAndSet(false, true)) {
			return
		}

		val path = "$dumpDir/memprof-peak-${System.currentTimeMillis()}.hprof"
		logger.info("MEMPROF|hprof_start|{}|path={}|usedMb={}", System.currentTimeMillis(), path, used shr 20)
		runCatching { Debug.dumpHprofData(path) }
			.onSuccess {
				logger.info("MEMPROF|hprof|{}|path={}|bytes={}", System.currentTimeMillis(), path, File(path).length())
			}.onFailure { failure ->
				logger.error("MEMPROF|hprof_failed|{}|path={}", System.currentTimeMillis(), path, failure)
			}
	}

	/** The `MEMPROF|sample|` line for one already-read [counters], so a caller reads ART's counters only once. */
	private fun sample(
		reason: String,
		counters: HeapCounters,
	): String {
		val runtime = Runtime.getRuntime()
		return buildString {
			append("MEMPROF|sample|").append(System.currentTimeMillis())
			append("|reason=").append(reason)
			append("|alloc=").append(counters.allocatedBytes)
			append("|freed=").append(counters.freedBytes)
			append("|gcCount=").append(stat("art.gc.gc-count"))
			append("|blockingGcCount=").append(counters.blockingGcCount)
			append("|gcTime=").append(stat("art.gc.gc-time"))
			append("|used=").append(counters.usedBytes)
			append("|total=").append(runtime.totalMemory())
			append("|max=").append(runtime.maxMemory())
		}
	}

	private fun stat(key: String): Long = Debug.getRuntimeStat(key)?.toLongOrNull() ?: -1L
}
