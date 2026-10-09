package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.memprof.Memprof
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.bootClassPaths
import kotlinx.coroutines.Job
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingProgressTracker
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Well-known key for the JVM library symbol index.
 *
 * Both the Kotlin and Java LSPs use this key to retrieve the
 * shared index from the [IndexRegistry][org.appdevforall.codeonthego.indexing.service.IndexRegistry].
 */
val JVM_LIBRARY_SYMBOL_INDEX = IndexKey<JvmSymbolIndex>("jvm-library-symbols")

/**
 * [JarIndexingService] that scans classpath JARs/AARs and builds a [JvmSymbolIndex].
 *
 * A pass runs on initialization and whenever a language server calls [refresh] at project setup. The
 * initialization pass is what indexes the libraries when those calls came before initialization,
 * since a [refresh] before then finds no index and does nothing. Every pass sets the active source
 * set and stats each JAR; one already indexed costs only that stat and fingerprint read and opens no
 * "Index libraries" phase, but a JAR the earlier pass failed to index still has no fingerprint and is
 * rescanned, opening a phase of its own.
 *
 * A pass that submits at least one JAR not already being indexed is profiled as the [Memprof] phase
 * "Index libraries", which ends, emitting `library_index_complete`, once the pass's JARs are indexed
 * and the index optimized.
 *
 * Thread safety: all methods are called from the
 * [IndexingServiceManager][org.appdevforall.codeonthego.indexing.service.IndexingServiceManager]'s
 * coroutine scope. The [JvmSymbolIndex] handles its own internal thread safety.
 */
class JvmLibraryIndexingService(
	context: Context,
	progressTracker: IndexingProgressTracker,
	workspaceSupplier: () -> Workspace? = { ProjectManagerImpl.getInstance().workspace },
	unreadableJarFilter: (Collection<String>) -> List<String> = { it.toList() },
) : JarIndexingService(context, progressTracker, workspaceSupplier, unreadableJarFilter = unreadableJarFilter) {
	companion object {
		const val ID = "jvm-indexing-service"
	}

	override val id = ID
	override val indexKey = JVM_LIBRARY_SYMBOL_INDEX
	override val dbName = JvmSymbolIndex.DB_NAME_DEFAULT
	override val indexName = JvmSymbolIndex.INDEX_NAME_LIBRARY

	override suspend fun initialize(registry: IndexRegistry) {
		super.initialize(registry)
		refresh()
	}

	override suspend fun completePass(
		jobs: List<Job>,
		newlyCounted: Int,
	) {
		/*
		 * Both LSPs refresh this index at project open. A pass that only folded into the other
		 * pass's running jobs must not open a second phase, or the report gets two rows and two
		 * markers for one indexing run.
		 */
		if (newlyCounted == 0) {
			return super.completePass(jobs, newlyCounted)
		}

		indexLibrariesPhase(jobs.size) { super.completePass(jobs, newlyCounted) }
	}

	override fun jarsToIndex(workspace: Workspace): Set<String> =
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

	private fun isIndexableJar(path: Path): Boolean {
		val ext = path.extension.lowercase()
		return ext == "jar" || ext == "aar"
	}
}

/**
 * Runs [action], a library pass over [jarCount] submitted JARs, as the "Index libraries" phase.
 *
 * The phase begins once the pass has submitted its JARs, so it leaves out the submit loop's own time
 * (a stat and a fingerprint read per JAR) although the submitted scans are already running by then.
 */
internal inline fun <R> indexLibrariesPhase(
	jarCount: Int,
	action: () -> R,
): R =
	Memprof.phase("Index libraries", "library_index_complete") { span ->
		if (span.isRecording) {
			span.put("jars", jarCount.toLong())
		}
		action()
	}
