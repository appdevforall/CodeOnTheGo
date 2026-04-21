package com.itsaky.androidide.lsp.kotlin.utils

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

fun VirtualFile.toNioPathOrNull(): Path? =
	runCatching { toNioPath() }.getOrNull()