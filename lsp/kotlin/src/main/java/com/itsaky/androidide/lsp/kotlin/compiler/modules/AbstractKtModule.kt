package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope

@OptIn(KaPlatformInterface::class)
internal abstract class AbstractKtModule(
	override val project: Project,
	override val directRegularDependencies: List<KtModule>,
) : KtModule, KaModuleBase() {

	private val baseSearchScope by lazy {
		val files = computeFiles(extended = true)
			.toList()

		GlobalSearchScope.filesScope(project, files)
	}

	override val baseContentScope: GlobalSearchScope
		get() = baseSearchScope

	override val directDependsOnDependencies: List<KtModule>
		get() = emptyList()

	override val directFriendDependencies: List<KtModule>
		get() = emptyList()
}