package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import java.nio.file.Path

@OptIn(KaPlatformInterface::class, KaExperimentalApi::class)
internal class NotUnderContentRootModule(
	override val id: String,
	project: Project,
	override val moduleDescription: String,
	directRegularDependencies: List<KtModule> = emptyList(),
	override val targetPlatform: TargetPlatform = JvmPlatforms.defaultJvmPlatform,
	override val file: PsiFile? = null,
) : KaNotUnderContentRootModule, AbstractKtModule(
	project, directRegularDependencies
) {
	override val name: String
		get() = id

	override val baseContentScope: GlobalSearchScope
		get() = if (file != null) GlobalSearchScope.fileScope(file) else GlobalSearchScope.EMPTY_SCOPE

	override val contentRoots: Set<Path>
		get() = file?.virtualFile?.toNioPath()?.let(::setOf) ?: emptySet()

	override fun computeFiles(extended: Boolean): Sequence<VirtualFile> = sequence {
		val vf = file?.virtualFile
		if (vf != null) {
			yield(vf)
		}
	}
}