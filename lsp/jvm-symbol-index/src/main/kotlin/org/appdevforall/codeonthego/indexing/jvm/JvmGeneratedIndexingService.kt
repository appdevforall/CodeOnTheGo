package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.tasks.cancelIfActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingService
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.extension

/**
 * Well-known key for the JVM generated-symbol index.
 *
 * Covers build-time-generated JARs such as R.jar that are excluded
 * from the main library index. Both the Kotlin and Java LSPs can
 * retrieve this index from the [IndexRegistry].
 */
val JVM_GENERATED_SYMBOL_INDEX = IndexKey<JvmSymbolIndex>("jvm-generated-symbols")

/**
 * [IndexingService] that scans build-generated JARs (R.jar, etc.) and
 * maintains a dedicated [JvmSymbolIndex] for them.
 *
 * A build completion re-scans every generated JAR's size and modification time (see
 * [jarFingerprint]) and only re-indexes those whose fingerprint changed, the same check
 * [JvmLibraryIndexingService] uses. This also catches a generated JAR rewritten outside an
 * in-app build, or left half-indexed by a killed process, which staying cached by path alone
 * would otherwise miss until some later build happened to touch it again.
 */
class JvmGeneratedIndexingService(
	private val context: Context,
) : IndexingService {
	companion object {
		const val ID = "jvm-generated-indexing-service"
		private const val DB_NAME = "jvm_generated_symbol_index.db"
		private const val INDEX_NAME = "jvm-generated-cache"
		private val log = LoggerFactory.getLogger(JvmGeneratedIndexingService::class.java)
	}

	override val id = ID

	override val providedKeys = listOf(JVM_GENERATED_SYMBOL_INDEX)

	private var generatedIndex: JvmSymbolIndex? = null
	private val indexingMutex = Mutex()
	private val coroutineScope = CoroutineScope(Dispatchers.Default)

	override suspend fun initialize(registry: IndexRegistry) {
		val index =
			JvmSymbolIndex.createSqliteIndex(
				context = context,
				dbName = DB_NAME,
				indexName = INDEX_NAME,
			)

		this.generatedIndex = index
		registry.register(JVM_GENERATED_SYMBOL_INDEX, index)
		log.info("JVM generated symbol index initialized")

		// Kick off an initial index pass for any already-built JARs.
		coroutineScope.launch {
			val jobs = indexingMutex.withLock { reindexGeneratedJars() }
			generatedIndex?.optimizeAfter(jobs)
		}
	}

	override suspend fun onBuildCompleted() {
		coroutineScope.launch {
			val jobs = indexingMutex.withLock { reindexGeneratedJars() }
			generatedIndex?.optimizeAfter(jobs)
		}
	}

	/** Submits every generated JAR whose fingerprint changed and returns the submitted jobs. */
	private suspend fun reindexGeneratedJars(): List<Job> {
		val index =
			this.generatedIndex ?: run {
				log.warn("Not indexing generated JARs - index not initialized.")
				return emptyList()
			}

		val workspace =
			ProjectManagerImpl.getInstance().workspace ?: run {
				log.warn("Not indexing generated JARs - workspace model not available.")
				return emptyList()
			}

		val generatedJars =
			workspace.subProjects
				.asSequence()
				.filterIsInstance<ModuleProject>()
				.filter { it.path != workspace.rootProject.path }
				.flatMap { project -> project.getIntermediateClasspaths() }
				.filter { jar -> jar.exists() && jar.toPath().extension.lowercase() == "jar" }
				.map { jar -> jar.absolutePath }
				.toSet()

		log.info("{} generated JARs found", generatedJars.size)

		// Make exactly these JARs visible; remove stale ones from scope.
		index.setActiveSources(generatedJars)

		val jobs = mutableListOf<Job>()
		for (jarPath in generatedJars) {
			val fingerprint = jarFingerprint(File(jarPath))
			if (index.sourceFingerprint(jarPath) != fingerprint) {
				jobs +=
					index.indexSource(jarPath, skipIfExists = true, fingerprint = fingerprint) { sourceId ->
						CombinedJarScanner.scan(Paths.get(jarPath), sourceId)
					}
			}
		}

		if (jobs.isNotEmpty()) {
			log.info("{} generated JARs submitted for background indexing", jobs.size)
		} else {
			log.info("All generated JARs already cached, nothing to index")
		}
		return jobs
	}

	override fun close() {
		coroutineScope.cancelIfActive("generated indexing service closed")
		generatedIndex?.close()
		generatedIndex = null
	}
}
