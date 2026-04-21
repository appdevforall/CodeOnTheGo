package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard

@Suppress("UnstableApiUsage")
class WriteAccessGuard: DocumentWriteAccessGuard() {
	override fun isWritable(p0: Document): Result {
		return success()
	}
}