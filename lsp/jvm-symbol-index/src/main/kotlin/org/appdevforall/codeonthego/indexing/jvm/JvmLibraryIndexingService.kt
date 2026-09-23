package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.bootClassPaths
import org.appdevforall.codeonthego.indexing.service.IndexKey
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
 * Thread safety: all methods are called from the
 * [IndexingServiceManager][org.appdevforall.codeonthego.indexing.service.IndexingServiceManager]'s
 * coroutine scope. The [JvmSymbolIndex] handles its own internal thread safety.
 */
class JvmLibraryIndexingService(
	context: Context,
) : JarIndexingService(context) {
	companion object {
		const val ID = "jvm-indexing-service"
	}

	override val id = ID
	override val indexKey = JVM_LIBRARY_SYMBOL_INDEX
	override val dbName = JvmSymbolIndex.DB_NAME_DEFAULT
	override val indexName = JvmSymbolIndex.INDEX_NAME_LIBRARY

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
