package com.itsaky.androidide.lsp.kotlin

import java.nio.file.Path

interface FileEventConsumer {

	fun onFileOpened(path: Path, content: String)
	fun onFileClosed(path: Path)

	fun onFileContentChanged(path: Path, content: String)
	fun onFileSaved(path: Path)
}