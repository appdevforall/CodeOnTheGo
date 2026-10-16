package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ApkExportTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val apk: File get() =
		File(tmp.root, "tree/app-debug.apk").apply {
			parentFile.mkdirs()
			writeText("apk")
		}

	@Test
	fun `the copy is named after the project and variant`() {
		val download = tmp.newFolder("Download")

		val copy = ApkExport.copyTo(apk, download, "My Application-debug")

		assertThat(copy).isEqualTo(File(download, "My Application-debug.apk"))
		assertThat(copy.readText()).isEqualTo("apk")
	}

	@Test
	fun `an earlier export is never overwritten`() {
		val download = tmp.newFolder("Download")
		File(download, "app-debug.apk").writeText("first")
		File(download, "app-debug (2).apk").writeText("second")

		val copy = ApkExport.copyTo(apk, download, "app-debug")

		assertThat(copy.name).isEqualTo("app-debug (3).apk")
		assertThat(File(download, "app-debug.apk").readText()).isEqualTo("first")
	}

	@Test
	fun `characters a file name cannot hold are replaced`() {
		val copy = ApkExport.copyTo(apk, tmp.newFolder("Download"), "a/b:c-debug")

		assertThat(copy.name).isEqualTo("a_b_c-debug.apk")
	}

	@Test
	fun `a missing Download folder is created`() {
		val download = File(tmp.root, "sdcard/Download")

		assertThat(ApkExport.copyTo(apk, download, "app-debug").isFile).isTrue()
	}

	@Test(expected = IOException::class)
	fun `a Download folder that cannot be created is reported`() {
		val blocker = tmp.newFile("sdcard")

		ApkExport.copyTo(apk, File(blocker, "Download"), "app-debug")
	}
}
