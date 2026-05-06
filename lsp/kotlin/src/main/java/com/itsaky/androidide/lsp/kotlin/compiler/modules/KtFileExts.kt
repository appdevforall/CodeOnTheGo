package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.jetbrains.kotlin.analysis.api.projectStructure.isDangling
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.UserDataProperty
import java.nio.file.Path

private val KT_LSP_COMPLETION_BACKING_FILE = Key<Path>("KT_LSP_COMPLETION_BACKING_FILE")
var KtFile.backingFilePath by UserDataProperty(KT_LSP_COMPLETION_BACKING_FILE)

internal inline fun <R> analyzeMaybeDangling(useSiteElement: KtElement, crossinline action: KaSession.() -> R): R {
	if (useSiteElement is KtFile && useSiteElement.isDangling && useSiteElement.copyOrigin != null) {
		return analyzeCopy(useSiteElement, KaDanglingFileResolutionMode.PREFER_SELF, action)
	}

	return analyze(useSiteElement, action)
}
