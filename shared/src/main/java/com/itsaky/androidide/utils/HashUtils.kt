package com.itsaky.androidide.utils

import java.io.File
import java.math.BigInteger
import java.nio.file.Path
import java.security.MessageDigest

fun File.sha256(): String {
	require(isFile) {
		"File must be a regular file."
	}

	return MessageDigest.getInstance("SHA-256").sha256(this)
}

/**
 * Calculates the SHA-256 checksum of this file.
 */
fun Path.sha256() = toFile().sha256()

/**
 * Calculates the SHA-256 checksum of a file.
 *
 * @param file The file to calculate the checksum for.
 * @return The SHA-256 checksum of the file.
 */
fun MessageDigest.sha256(file: File): String {
	this.reset()

	val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
	return file.inputStream().use { input ->
		while (true) {
			val bytesRead = input.read(buffer)
			if (bytesRead <= 0) break
			update(buffer, 0, bytesRead)
		}

		var checksum = BigInteger(1, digest()).toString(16)
		if (checksum.length < 64) {
			checksum = "0".repeat(64 - checksum.length) + checksum
		}

		checksum
	}
}
