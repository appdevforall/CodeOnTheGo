package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin
import java.io.File

interface FileOpenExtension : IPlugin {

    fun canHandleFileOpen(file: File): Boolean = false

    fun handleFileOpen(file: File): Boolean = false

    fun onFileOpened(file: File) {}

    fun getFileTabMenuItems(file: File): List<FileTabMenuItem> = emptyList()

    fun onFileClosed(file: File) {}
}

data class FileTabMenuItem(
    val id: String,
    val title: String,
    val icon: Int? = null,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val order: Int = 0,
    val action: () -> Unit
)
