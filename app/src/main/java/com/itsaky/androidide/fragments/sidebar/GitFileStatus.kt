package com.itsaky.androidide.fragments.sidebar

data class GitFileStatus(
    val filePath: String,
    val status: String,
    var isChecked: Boolean = true // Default to checked
)