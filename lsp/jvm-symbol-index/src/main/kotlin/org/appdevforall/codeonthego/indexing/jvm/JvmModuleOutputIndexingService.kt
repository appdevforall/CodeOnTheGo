package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.JavaModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import kotlin.io.path.extension

/**
 * Well-known key for the index of the project modules' own compiled output JARs.
 *
 * A scope of its own rather than part of [JVM_LIBRARY_SYMBOL_INDEX] or
 * [JVM_GENERATED_SYMBOL_INDEX]: the Kotlin LSP resolves project classes from its source index and
 * also reads those two, so folding these JARs into either would show it every project class twice.
 * Only the Java LSP, which resolves project classes from the compile classpath, reads this one.
 */
val JVM_MODULE_OUTPUT_SYMBOL_INDEX = IndexKey<JvmSymbolIndex>("jvm-module-output-symbols")

/**
 * [JarIndexingService] over each project module's own compiled output JAR.
 *
 * These are the entries `getCompileClasspaths()` holds and
 * `getCompileClasspaths(excludeSourceGeneratedClassPath = true)`, the library index's set, leaves
 * out: an Android module's [generated JAR][AndroidModule.getGeneratedJar] and a Java module's
 * [classes JAR][JavaModule.getClassesJar]. Every module is covered, so the JARs of the project
 * modules a module compiles against are too. `R.jar` is not, since the library and generated
 * indexes already hold it.
 *
 * A pass runs on initialization, after every build and on every project sync. Each JAR is
 * re-indexed only when its [fingerprint][jarFingerprint] changed, so a build that failed without
 * rewriting a JAR costs one file stat per module.
 */
class JvmModuleOutputIndexingService(
	context: Context,
	workspaceSupplier: () -> Workspace? = { ProjectManagerImpl.getInstance().workspace },
) : JarIndexingService(context, workspaceSupplier) {
	companion object {
		const val ID = "jvm-module-output-indexing-service"
		private const val DB_NAME = "jvm_module_output_symbol_index.db"
		private const val INDEX_NAME = "jvm-module-output-cache"
	}

	override val id = ID
	override val indexKey = JVM_MODULE_OUTPUT_SYMBOL_INDEX
	override val dbName = DB_NAME
	override val indexName = INDEX_NAME

	override suspend fun initialize(registry: IndexRegistry) {
		super.initialize(registry)
		refresh()
	}

	override suspend fun onBuildCompleted() {
		refresh()
	}

	override fun jarsToIndex(workspace: Workspace): Set<String> = moduleOutputJars(workspace)
}

/** Absolute paths of the built output JARs of every module in [workspace]. Stats each JAR. */
internal fun moduleOutputJars(workspace: Workspace): Set<String> =
	workspace.subProjects
		.asSequence()
		.filterIsInstance<ModuleProject>()
		.filter { it.path != workspace.rootProject.path }
		.mapNotNull { module ->
			when (module) {
				is AndroidModule -> module.getGeneratedJar()
				is JavaModule -> module.getClassesJar()
				else -> null
			}
		}.filter { jar -> jar.exists() && jar.toPath().extension.lowercase() == "jar" }
		.map { jar -> jar.absolutePath }
		.toSet()
