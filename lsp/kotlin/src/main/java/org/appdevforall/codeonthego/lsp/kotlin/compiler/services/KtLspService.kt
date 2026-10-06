package org.appdevforall.codeonthego.lsp.kotlin.compiler.services

import org.appdevforall.codeonthego.lsp.kotlin.compiler.index.KtSymbolIndex
import org.appdevforall.codeonthego.lsp.kotlin.compiler.modules.KtModule
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject

internal interface KtLspService {

	fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>,
	)
}