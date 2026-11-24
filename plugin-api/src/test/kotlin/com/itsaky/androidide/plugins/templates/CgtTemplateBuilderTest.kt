package com.itsaky.androidide.plugins.templates

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
			val east = template().build(tmp.newFolder()).readBytes()
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"))
			val west = template().build(tmp.newFolder()).readBytes()

			assertThat(west).isEqualTo(east)
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
		template().build(dir)

		val cgt = template(description = "Changed").build(dir)

		assertThat(templateJson(cgt)).contains("\"description\": \"Changed\"")
	}

	private fun template(description: String = "A test template") =
		CgtTemplateBuilder("Test App")
			.description(description)
			.thumbnail(byteArrayOf(1, 2, 3))
			.addTextParameter("Package name", "NPM_NAME", "my-app")
			.addTemplateFile("README.md", "# {{ APP_NAME }}")
			.addStaticFile(".gitignore", "node_modules/")
			.addStaticFile("data.bin", byteArrayOf(4, 5, 6))

	private fun templateJson(cgt: File): String =
		ZipFile(cgt).use { zip ->
			zip.getInputStream(zip.getEntry("TestApp/template/template.json")).bufferedReader().readText()
		}
}
