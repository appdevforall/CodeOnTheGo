package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions

class AnalysisPermissionOptions : KotlinAnalysisPermissionOptions {
	override val defaultIsAnalysisAllowedOnEdt: Boolean get() = false
	override val defaultIsAnalysisAllowedInWriteAction: Boolean get() = true
}