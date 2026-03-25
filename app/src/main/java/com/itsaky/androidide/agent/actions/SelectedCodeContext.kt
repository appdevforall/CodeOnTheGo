package com.itsaky.androidide.agent.actions

data class SelectedCodeContext(
    val selectedText: String,
    val fileName: String?,
    val filePath: String?,
    val fileExtension: String?,
    val lineStart: Int?,
    val lineEnd: Int?,
    val selectionLength: Int
)
