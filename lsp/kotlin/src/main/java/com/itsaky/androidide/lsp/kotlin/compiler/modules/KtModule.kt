package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

@OptIn(KaPlatformInterface::class)
internal interface KtModule : KaModule {
	val id: String

	val contentRoots: Set<Path>

	override val directRegularDependencies: List<KtModule>
	override val directDependsOnDependencies: List<KtModule>
	override val directFriendDependencies: List<KtModule>

	fun computeFiles(extended: Boolean): Sequence<VirtualFile>
}

/**
 * Whether this module holds sources rather than binaries.
 *
 * Asks the Analysis API's own question ([KaSourceModule]) rather than naming one concrete class: a
 * nominal `is KtSourceModule` check silently classified every other source-module implementation as a
 * library, which left `javaSourceRoots` empty in `AbstractCompilationEnvironment.initialize` and so
 * never put workspace `.java` roots on the Java classpath.
 */
internal val KtModule.isSourceModule: Boolean
	get() = this is KaSourceModule

internal fun List<KtModule>.asFlatSequence(): Sequence<KtModule> {
	val processedModules = mutableSetOf<String>()
	return this.asSequence().flatMap { getModuleFlatSequence(it, processedModules) }
}

private fun getModuleFlatSequence(
	ktModule: KtModule,
	processed: MutableSet<String>,
): Sequence<KtModule> =
	sequence {
		if (processed.contains(ktModule.id)) return@sequence

		yield(ktModule)
		processed.add(ktModule.id)

		ktModule.directRegularDependencies.forEach { dependency ->
			yieldAll(getModuleFlatSequence(dependency, processed))
		}
	}
