package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
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

internal val KtModule.isSourceModule: Boolean
	get() = this is KtSourceModule

internal fun List<KtModule>.asFlatSequence(): Sequence<KtModule> {
	val processedModules = mutableSetOf<String>()
	return this.asSequence().flatMap { getModuleFlatSequence(it, processedModules) }
}

private fun getModuleFlatSequence(ktModule: KtModule, processed: MutableSet<String>): Sequence<KtModule> = sequence {
	if (processed.contains(ktModule.id)) return@sequence

	yield(ktModule)
	processed.add(ktModule.id)

	ktModule.directRegularDependencies.forEach { dependency ->
		yieldAll(getModuleFlatSequence(dependency, processed))
	}
}