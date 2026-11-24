package com.itsaky.androidide.plugins.templates

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipFile

/**
 * Plugins build their templates on every activation (ADFA-7287). The same template must give the
 * same bytes, and building it again must leave the file alone.
 */
class CgtTemplateBuilderTest {
	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun everyEntryHasTheFixedTime() {
		val cgt = template().build(tmp.newFolder())

		ZipFile(cgt).use { zip ->
			val entries = zip.entries().toList()
			assertThat(entries).isNotEmpty()
			for (entry in entries) {
				assertWithMessage(entry.name).that(entry.time).isEqualTo(CgtTemplateBuilder.fixedEntryTime())
			}
		}
	}

	@Test
	fun sameTemplateGivesTheSameBytesInAnotherTimeZone() {
		val zone = TimeZone.getDefault()
		try {
			// 17 hours apart in 1980.
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
			val tokyo = template().build(tmp.newFolder()).readBytes()
			TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
			val losAngeles = template().build(tmp.newFolder()).readBytes()

			assertWithMessage("the bytes differ between time zones").that(losAngeles.contentEquals(tokyo)).isTrue()
		} finally {
			TimeZone.setDefault(zone)
		}
	}

	@Test
	fun buildingAgainLeavesAnIdenticalFileAlone() {
		val dir = tmp.newFolder()
		val cgt = template().build(dir)
		val old = 1_000_000_000_000L
		assertThat(cgt.setLastModified(old)).isTrue()

		template().build(dir)

		assertWithMessage("the identical .cgt was written again").that(cgt.lastModified()).isEqualTo(old)
	}

	@Test
	fun buildingAChangedTemplateReplacesTheFile() {
		val dir = tmp.newFolder()
		val before = template().build(dir).length()

		// One byte changes and the length doesn't, so only a byte comparison sees the change.
		val cgt = template(data = byteArrayOf(4, 5, 7)).build(dir)

		assertThat(cgt.length()).isEqualTo(before)
		assertThat(entry(cgt, "TestApp/data.bin")).isEqualTo(byteArrayOf(4, 5, 7))
	}

	@Test
	fun buildingOverAnUnreadableFileWritesIt() {
		val dir = tmp.newFolder()
		val cgt = template().build(dir)
		cgt.writeBytes(ByteArray(cgt.length().toInt()))
		assertThat(cgt.setReadable(false)).isTrue()
		assumeFalse("root can read any file", cgt.canRead())

		template().build(dir)

		assertThat(cgt.setReadable(true)).isTrue()
		assertThat(cgt.readBytes().contentEquals(template().build(tmp.newFolder()).readBytes())).isTrue()
	}

	private fun template(data: ByteArray = byteArrayOf(4, 5, 6)) =
		CgtTemplateBuilder("Test App")
			.description("A test template")
			.thumbnail(byteArrayOf(1, 2, 3))
			.addTextParameter("Package name", "NPM_NAME", "my-app")
			.addTemplateFile("README.md", "# {{ APP_NAME }}")
			.addStaticFile(".gitignore", "node_modules/")
			.addStaticFile("data.bin", data)

	private fun entry(
		cgt: File,
		name: String,
	): ByteArray = ZipFile(cgt).use { zip -> zip.getInputStream(zip.getEntry(name)).readBytes() }
}
