package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for behavior differences introduced when replacing
 * com.blankj:utilcodex's FileUtils with an in-house implementation (ADFA-4649).
 */
class FileUtilsTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `rename does not overwrite an existing file at the destination`() {
		val source = tempFolder.newFile("a.txt").apply { writeText("source") }
		val destination = tempFolder.newFile("b.txt").apply { writeText("destination") }

		val result = FileUtils.rename(source, destination.name)

		assertThat(result).isFalse()
		assertThat(destination.readText()).isEqualTo("destination")
		assertThat(source.exists()).isTrue()
	}

	@Test
	fun `rename succeeds when the destination does not exist`() {
		val source = tempFolder.newFile("a.txt").apply { writeText("source") }
		val destination = tempFolder.root.resolve("c.txt")

		val result = FileUtils.rename(source, destination.name)

		assertThat(result).isTrue()
		assertThat(destination.readText()).isEqualTo("source")
	}

	@Test
	fun `isUtf8 ignores invalid bytes beyond the sampled header`() {
		val file = tempFolder.newFile("valid-header.bin")
		file.writeBytes(ByteArray(24) { 'a'.code.toByte() } + byteArrayOf(0xFF.toByte()))

		assertThat(FileUtils.isUtf8(file)).isTrue()
	}

	@Test
	fun `isUtf8 rejects a file with invalid bytes in its header`() {
		val file = tempFolder.newFile("invalid-header.bin")
		file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

		assertThat(FileUtils.isUtf8(file)).isFalse()
	}

	@Test
	fun `isUtf8 returns false for an empty file`() {
		val file = tempFolder.newFile("empty.txt")

		assertThat(FileUtils.isUtf8(file)).isFalse()
	}
}
