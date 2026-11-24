package com.itsaky.androidide.lsp.kotlin.compiler.vfs

import org.jetbrains.kotlin.com.intellij.openapi.util.io.BufferExposingByteArrayInputStream
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem
import java.io.InputStream
import java.io.OutputStream

/**
 * A JAR entry, or the JAR root, served from a [ListingJarHandler].
 *
 * Paths, lengths and time stamps follow IntelliJ's `CoreJarVirtualFile`: a directory has length
 * -1 and time stamp -1, and a file has the JAR's modification time.
 */
internal class ListingJarVirtualFile(
	val handler: ListingJarHandler,
	private val index: Int,
	private val parent: ListingJarVirtualFile?,
) : VirtualFile() {
	private val name = handler.listing.shortName(index)

	private val path: String =
		if (parent == null) {
			FileUtil.toSystemIndependentName(handler.jarPath) + "!/"
		} else {
			parent.path.let { if (it.endsWith('/')) it + name else "$it/$name" }
		}

	private val isDirectory = handler.listing.isDirectory(index)

	override fun getName(): String = name

	override fun getFileSystem(): VirtualFileSystem = handler.fileSystem

	override fun getPath(): String = path

	override fun isWritable(): Boolean = false

	override fun isDirectory(): Boolean = isDirectory

	override fun isValid(): Boolean = handler.isValid

	override fun getParent(): VirtualFile? = parent

	override fun getChildren(): Array<VirtualFile> = if (isDirectory) handler.childrenOf(index).toTypedArray() else EMPTY_ARRAY

	override fun findChild(name: String): VirtualFile? = if (isDirectory) handler.findChild(index, name) else null

	override fun getOutputStream(
		requestor: Any?,
		newModificationStamp: Long,
		newTimeStamp: Long,
	): OutputStream = throw UnsupportedOperationException("JarFileSystem is read-only")

	override fun contentsToByteArray(): ByteArray = handler.contentsOf(index)

	override fun getTimeStamp(): Long = if (isDirectory) DIRECTORY_STAMP else handler.jarTimeStamp

	override fun getLength(): Long = if (isDirectory) DIRECTORY_LENGTH else handler.listing.size(index)

	override fun refresh(
		asynchronous: Boolean,
		recursive: Boolean,
		postRunnable: Runnable?,
	) = Unit

	override fun getInputStream(): InputStream = BufferExposingByteArrayInputStream(contentsToByteArray())

	override fun getModificationStamp(): Long = 0

	private companion object {
		const val DIRECTORY_LENGTH = -1L
		const val DIRECTORY_STAMP = -1L
	}
}
