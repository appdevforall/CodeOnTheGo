package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for [ImageUtils.getImageType]'s magic-number sniffing (ADFA-4649).
 */
class ImageUtilsTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `getImageType recognizes a valid little-endian TIFF header`() {
		val file = tempFolder.newFile("valid.tiff")
		file.writeBytes(byteArrayOf(0x49, 0x49, 0x2A, 0x00))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_TIFF)
	}

	@Test
	fun `getImageType recognizes a valid big-endian TIFF header`() {
		val file = tempFolder.newFile("valid-be.tiff")
		file.writeBytes(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_TIFF)
	}

	@Test
	fun `getImageType rejects an II-prefixed header with the wrong signature`() {
		val file = tempFolder.newFile("not-tiff.bin")
		file.writeBytes(byteArrayOf(0x49, 0x49, 0x00, 0x00))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_UNKNOWN)
	}

	@Test
	fun `getImageType recognizes a JPEG header`() {
		val file = tempFolder.newFile("valid.jpg")
		file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_JPG)
	}

	@Test
	fun `getImageType recognizes a PNG header`() {
		val file = tempFolder.newFile("valid.png")
		file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_PNG)
	}

	@Test
	fun `getImageType recognizes a GIF header`() {
		val file = tempFolder.newFile("valid.gif")
		file.writeBytes(byteArrayOf(0x47, 0x49, 0x46, 0x38))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_GIF)
	}

	@Test
	fun `getImageType recognizes a BMP header`() {
		val file = tempFolder.newFile("valid.bmp")
		file.writeBytes(byteArrayOf(0x42, 0x4D, 0x00, 0x00))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_BMP)
	}

	@Test
	fun `getImageType recognizes an ICO header`() {
		val file = tempFolder.newFile("valid.ico")
		file.writeBytes(byteArrayOf(0x00, 0x00, 0x01, 0x00))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_ICO)
	}

	@Test
	fun `getImageType recognizes a WEBP header`() {
		val file = tempFolder.newFile("valid.webp")
		file.writeBytes(
			byteArrayOf(
				'R'.code.toByte(),
				'I'.code.toByte(),
				'F'.code.toByte(),
				'F'.code.toByte(),
				0x00,
				0x00,
				0x00,
				0x00,
				'W'.code.toByte(),
				'E'.code.toByte(),
				'B'.code.toByte(),
				'P'.code.toByte(),
			),
		)

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_WEBP)
	}

	@Test
	fun `getImageType returns unknown for a header shorter than 4 bytes`() {
		val file = tempFolder.newFile("too-short.bin")
		file.writeBytes(byteArrayOf(0x00, 0x01))

		assertThat(ImageUtils.getImageType(file)).isEqualTo(ImageUtils.ImageType.TYPE_UNKNOWN)
	}

	@Test
	fun `getImageType returns unknown for a directory`() {
		val dir = tempFolder.newFolder("a-directory")

		assertThat(ImageUtils.getImageType(dir)).isEqualTo(ImageUtils.ImageType.TYPE_UNKNOWN)
	}

	@Test
	fun `getImageType returns unknown for a missing file`() {
		val missing = tempFolder.root.resolve("does-not-exist.png")

		assertThat(ImageUtils.getImageType(missing)).isEqualTo(ImageUtils.ImageType.TYPE_UNKNOWN)
	}
}
