package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.models.bootClassPaths
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
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension

/**
 * Well-known key for the JVM library symbol index.
 *
 * Both the Kotlin and Java LSPs use this key to retrieve the
 * shared index from the [IndexRegistry].
 */
val JVM_LIBRARY_SYMBOL_INDEX = IndexKey<JvmSymbolIndex>("jvm-library-symbols")

/**
 * [IndexingService] that scans classpath JARs/AARs and builds
 * a [JvmSymbolIndex].
 *
 * Thread safety: all methods are called from the
 * [IndexingServiceManager][org.appdevforall.codeonthego.indexing.service.IndexingServiceManager]'s
 * coroutine scope. The [JvmSymbolIndex] handles its own internal thread safety.
 */
class JvmLibraryIndexingService(
	private val context: Context,
) : IndexingService {
	companion object {
		const val ID = "jvm-indexing-service"
		private val log = LoggerFactory.getLogger(JvmLibraryIndexingService::class.java)
	}

	override val id = ID

	override val providedKeys = listOf(JVM_LIBRARY_SYMBOL_INDEX)

	private var libraryIndex: JvmSymbolIndex? = null
	private var indexingMutex = Mutex()
	private val coroutineScope = CoroutineScope(Dispatchers.Default)

	override suspend fun initialize(registry: IndexRegistry) {
		val jvmIndex =
			JvmSymbolIndex.createSqliteIndex(
				context = context,
				dbName = JvmSymbolIndex.DB_NAME_DEFAULT,
				indexName = JvmSymbolIndex.INDEX_NAME_LIBRARY,
			)

		this.libraryIndex = jvmIndex
		registry.register(JVM_LIBRARY_SYMBOL_INDEX, jvmIndex)
		log.info("JVM symbol index initialized")
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("UNUSED")
	fun onProjectSynced() {
		refresh()
	}

	fun refresh() {
		coroutineScope.launch {
			val jobs = indexingMutex.withLock { reindexLibraries() }
			libraryIndex?.optimizeAfter(jobs)
		}
	}

	/** Submits every library JAR that needs indexing and returns the submitted jobs. */
	private suspend fun reindexLibraries(): List<Job> {
		val index =
			this.libraryIndex ?: run {
				log.warn("Not indexing libraries. Index not initialized.")
				return emptyList()
			}

		val workspace =
			ProjectManagerImpl.getInstance().workspace ?: run {
				log.warn("Not indexing libraries. Workspace model not available.")
				return emptyList()
			}

		val currentJars =
			workspace.subProjects
				.asSequence()
				.filterIsInstance<ModuleProject>()
				.filter { it.path != workspace.rootProject.path }
				.flatMap { project ->
					buildList {
						if (project is AndroidModule) {
							addAll(project.bootClassPaths)
						}

						addAll(project.getCompileClasspaths(excludeSourceGeneratedClassPath = true))
					}
				}.filter { jar -> jar.exists() && isIndexableJar(jar.toPath()) }
				.map { jar -> jar.absolutePath }
				.toSet()

		log.info("{} JARs on classpath", currentJars.size)

		/*
		 * Step 1: Set the active set - this is instant. JARs not in the set become invisible to
		 * queries; JARs in the set that are already cached become visible immediately.
		 */
		index.setActiveSources(currentJars)

		/*
		 * Step 2: Index any JAR that is not cached, or was cached with a different fingerprint:
		 * a JAR rebuilt at the same path (a snapshot, a local file dependency) is re-scanned.
		 * Newly cached JARs are automatically visible because they're already in the active set.
		 */
		val jobs = mutableListOf<Job>()
		for (jarPath in currentJars) {
			val fingerprint = jarFingerprint(File(jarPath))
			if (index.sourceFingerprint(jarPath) != fingerprint) {
				jobs +=
					index.indexSource(jarPath, skipIfExists = true, fingerprint = fingerprint) { sourceId ->
						CombinedJarScanner.scan(Paths.get(jarPath), sourceId)
					}
			}
		}

		if (jobs.isNotEmpty()) {
			log.info("{} new or changed JARs submitted for background indexing", jobs.size)
		} else {
			log.info("All JARs already cached, nothing to index")
		}
		return jobs
	}

	override fun close() {
		coroutineScope.cancelIfActive("indexing service closed")
		libraryIndex?.close()
		libraryIndex = null
	}

	private fun isIndexableJar(path: Path): Boolean {
		val ext = path.extension.lowercase()
		return ext == "jar" || ext == "aar"
	}
}

/**
 * Identifies the content of [jar] by its size and last-modified time, which a rewrite of the file
 * changes without the cost of reading it. Stats the file, so never call it on the main thread.
 */
internal fun jarFingerprint(jar: File): String = "${jar.length()}:${jar.lastModified()}"
