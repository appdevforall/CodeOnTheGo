package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.models.CompletionItem

interface KotlinCompletion {
   suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem>
}