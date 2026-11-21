package com.itsaky.androidide.assets.addons

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object AtomicFiles {
	/** Moves [temp] to [dest], replacing it, in one step where the filesystem can. */
	fun moveInto(
		temp: File,
		dest: File,
	) {
		try {
			Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
		} catch (e: AtomicMoveNotSupportedException) {
			Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
		}
	}

	/**
	 * Fills a scratch file next to the destination with [write], then lets [finalName] pick the
	 * destination's name from it and moves it there. The scratch name ends in `.partial`, which no
	 * loader of plugins or templates reads, and the scratch file is deleted on any failure, so an
	 * interrupted copy never leaves a truncated file under a final name.
	 */
	fun writeInto(
		destDir: File,
		write: (File) -> Unit,
		finalName: (File) -> String,
	): File {
		if (!destDir.isDirectory && !destDir.mkdirs() && !destDir.isDirectory) {
			throw IOException("Could not create ${destDir.absolutePath}")
		}
		val temp = File(destDir, ".${UUID.randomUUID()}.partial")
		try {
			write(temp)
			val dest = File(destDir, finalName(temp))
			moveInto(temp, dest)
			return dest
		} finally {
			// Gone already after a successful move; only a failure leaves it.
			temp.delete()
		}
	}
}
