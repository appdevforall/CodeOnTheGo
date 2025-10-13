package com.itsaky.androidide.models

data class GitFileStatus(
    val filePath: String,
    val status: String,
    var isChecked: Boolean = true // Default to checked
)