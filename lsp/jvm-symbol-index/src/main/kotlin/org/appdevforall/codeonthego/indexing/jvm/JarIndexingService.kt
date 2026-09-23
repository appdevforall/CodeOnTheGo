package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.tasks.cancelIfActive
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingProgressTracker
import org.appdevforall.codeonthego.indexing.service.IndexingService
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths

/**
 * Base [IndexingService] for a persistent [JvmSymbolIndex] over a set of JARs derived from the
 * project model.
 *
 * Owns the index instance, the single-submitter mutex that serializes [refresh] passes, and the
 * fingerprint-driven reindex loop: only a JAR whose [jarFingerprint] changed since it was last
 * indexed is re-scanned, and [JvmSymbolIndex.optimizeAfter] runs once the whole pass finishes. A
 * subclass supplies [indexKey], [dbName], [indexName] and [jarsToIndex].
 *
 * Every pass reports the JARs it submits to [progressTracker] and closes its tracker pass in a
 * `finally`, so a pass that throws or is cancelled cannot leave the indexing state stuck.
 *
 * The scope carries a [SupervisorJob] and a [CoroutineExceptionHandler]: a [refresh] whose
 * [jarsToIndex] throws logs the failure instead of cancelling the scope, so a later [refresh]
 * still runs its pass.
 */
abstract class JarIndexingService(
	protected val context: Context,
	private val progressTracker: IndexingProgressTracker,
	private val workspaceSupplier: () -> Workspace? = { ProjectManagerImpl.getInstance().workspace },
) : IndexingService {
	companion object {
		private val log = LoggerFactory.getLogger(JarIndexingService::class.java)
	}

	/** Key this service's index is registered under. */
	protected abstract val indexKey: IndexKey<JvmSymbolIndex>

	/** Database file backing this service's index. */
	protected abstract val dbName: String

	/** Index name within [dbName]. */
	protected abstract val indexName: String

	override val providedKeys: List<IndexKey<*>> get() = listOf(indexKey)

	/** Every JAR that should be indexed and visible for the current [workspace]. */
	protected abstract fun jarsToIndex(workspace: Workspace): Set<String>

	private var jarIndex: JvmSymbolIndex? = null
	private val indexingMutex = Mutex()
	private val coroutineScope =
		CoroutineScope(
			SupervisorJob() +
				Dispatchers.Default +
				CoroutineExceptionHandler { _, throwable ->
					log.error("[{}] Unhandled exception while indexing", id, throwable)
				},
		)

	override suspend fun initialize(registry: IndexRegistry) {
		val jvmIndex =
			JvmSymbolIndex.createSqliteIndex(
				context = context,
				dbName = dbName,
				indexName = indexName,
			)

		this.jarIndex = jvmIndex
		registry.register(indexKey, jvmIndex)
		log.info("[{}] index initialized", id)
	}

	/**
	 * Submits a reindex pass under the single-submitter mutex, then optimizes once it completes.
	 *
	 * Returns the pass's job, which completes once every JAR it submitted is indexed.
	 */
	fun refresh(): Job =
		coroutineScope.launch {
			progressTracker.openPass().use { pass ->
				val jobs = indexingMutex.withLock { reindex(pass) }
				completePass(jobs, pass.newlyCounted)
			}
		}

	/**
	 * Waits for [jobs], every JAR one pass submitted, then optimizes the index; runs outside the mutex.
	 *
	 * [newlyCounted] is how many of [jobs] the pass added to the progress total rather than folded
	 * into another pass's still-running jobs.
	 */
	protected open suspend fun completePass(
		jobs: List<Job>,
		newlyCounted: Int,
	) {
		jarIndex?.optimizeAfter(jobs)
	}

	/**
	 * Submits every JAR whose fingerprint changed since it was last indexed, tracking each on [pass],
	 * and returns the submitted jobs.
	 */
	private suspend fun reindex(pass: IndexingProgressTracker.Pass): List<Job> {
		val index =
			this.jarIndex ?: run {
				log.warn("[{}] Not indexing. Index not initialized.", id)
				return emptyList()
			}

		val workspace =
			workspaceSupplier() ?: run {
				log.warn("[{}] Not indexing. Workspace model not available.", id)
				return emptyList()
			}

		val jars = jarsToIndex(workspace)
		log.info("[{}] {} JARs found", id, jars.size)

		/*
		 * Step 1: Set the active set - this is instant. JARs not in the set become invisible to
		 * queries; JARs in the set that are already cached become visible immediately.
		 */
		index.setActiveSources(jars)

		/*
		 * Step 2: Index any JAR that is not cached, or was cached with a different fingerprint:
		 * a JAR rebuilt at the same path (a snapshot, a local file dependency) is re-scanned.
		 * Newly cached JARs are automatically visible because they're already in the active set.
		 */
		val jobs = mutableListOf<Job>()
		for (jarPath in jars) {
			val fingerprint = jarFingerprint(File(jarPath))
			if (index.sourceFingerprint(jarPath) != fingerprint) {
				val job =
					index.indexSource(jarPath, skipIfExists = true, fingerprint = fingerprint) { sourceId ->
						CombinedJarScanner.scan(Paths.get(jarPath), sourceId)
					}
				pass.track(jarPath, job)
				jobs += job
			}
		}

		if (jobs.isNotEmpty()) {
			log.info("[{}] {} new or changed JARs submitted for background indexing", id, jobs.size)
		} else {
			log.info("[{}] All JARs already cached, nothing to index", id)
		}
		return jobs
	}

	override fun close() {
		coroutineScope.cancelIfActive("$id indexing service closed")
		jarIndex?.close()
		jarIndex = null
	}
}

/**
 * Identifies the content of [jar] by its size and last-modified time, which a rewrite of the file
 * changes without the cost of reading it. Stats the file, so never call it on the main thread.
 *
 * Accepted limitation: a rewrite that keeps the exact same size and lands within the
 * filesystem's modification-time granularity (a second on some filesystems) produces the same
 * fingerprint as the original, so that rewrite goes undetected.
 */
internal fun jarFingerprint(jar: File): String = "${jar.length()}:${jar.lastModified()}"
