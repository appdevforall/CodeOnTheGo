package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.tasks.cancelIfActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingService
import org.slf4j.LoggerFactory
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
 * Generated JARs are re-indexed unconditionally on every build completion
 * because their contents change (new R-field values, new resource IDs) even
 * when the set of JARs doesn't change.
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
		val index = JvmSymbolIndex.createSqliteIndex(
			context = context,
			dbName = DB_NAME,
			indexName = INDEX_NAME,
		)

		this.generatedIndex = index
		registry.register(JVM_GENERATED_SYMBOL_INDEX, index)
		log.info("JVM generated symbol index initialized")

		// Kick off an initial index pass for any already-built JARs.
		coroutineScope.launch {
			indexingMutex.withLock {
				reindexGeneratedJars(forceReindex = false)
			}
		}
	}

	override suspend fun onBuildCompleted() {
		// Generated JARs (especially R.jar) always change after a build —
		// their field values are regenerated. Force a full re-index.
		coroutineScope.launch {
			indexingMutex.withLock {
				reindexGeneratedJars(forceReindex = true)
			}
		}
	}

	private suspend fun reindexGeneratedJars(forceReindex: Boolean) {
		val index = this.generatedIndex ?: run {
			log.warn("Not indexing generated JARs — index not initialized.")
			return
		}

		val workspace = ProjectManagerImpl.getInstance().workspace ?: run {
			log.warn("Not indexing generated JARs — workspace model not available.")
			return
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

		var submitted = 0
		for (jarPath in generatedJars) {
			if (forceReindex || !index.isCached(jarPath)) {
				submitted++
				index.indexSource(jarPath, skipIfExists = false) { sourceId ->
					CombinedJarScanner.scan(Paths.get(jarPath), sourceId)
				}
			}
		}

		if (submitted > 0) {
			log.info("{} generated JARs submitted for background indexing (force={})", submitted, forceReindex)
		} else {
			log.info("All generated JARs already cached, nothing to index")
		}
	}

	override fun close() {
		coroutineScope.cancelIfActive("generated indexing service closed")
		generatedIndex?.close()
		generatedIndex = null
	}
}
