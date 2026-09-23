package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
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
 * [JarIndexingService] that scans build-generated JARs (R.jar, etc.) and
 * maintains a dedicated [JvmSymbolIndex] for them.
 *
 * A build completion re-scans every generated JAR's size and modification time (see
 * [jarFingerprint]) and only re-indexes those whose fingerprint changed, the same check
 * [JvmLibraryIndexingService] uses. This also catches a generated JAR rewritten outside an
 * in-app build, or left half-indexed by a killed process, which staying cached by path alone
 * would otherwise miss until some later build happened to touch it again.
 */
class JvmGeneratedIndexingService(
	context: Context,
) : JarIndexingService(context) {
	companion object {
		const val ID = "jvm-generated-indexing-service"
		private const val DB_NAME = "jvm_generated_symbol_index.db"
		private const val INDEX_NAME = "jvm-generated-cache"
	}

	override val id = ID
	override val indexKey = JVM_GENERATED_SYMBOL_INDEX
	override val dbName = DB_NAME
	override val indexName = INDEX_NAME

	override suspend fun initialize(registry: IndexRegistry) {
		super.initialize(registry)
		// Kick off an initial index pass for any already-built JARs.
		refresh()
	}

	override suspend fun onBuildCompleted() {
		refresh()
	}

	override fun jarsToIndex(workspace: Workspace): Set<String> =
		workspace.subProjects
			.asSequence()
			.filterIsInstance<ModuleProject>()
			.filter { it.path != workspace.rootProject.path }
			.flatMap { project -> project.getIntermediateClasspaths() }
			.filter { jar -> jar.exists() && jar.toPath().extension.lowercase() == "jar" }
			.map { jar -> jar.absolutePath }
			.toSet()
}
