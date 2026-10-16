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
	fun `no listing means nothing to export`() {
		assertThat(ApkExport.resolve(File(tmp.root, "out/output-metadata.json"), report(), tmp.root))
			.isEqualTo(ApkExport.Exportable.NotBuilt)
	}

	@Test
	fun `a malformed listing is nothing to export, not a crash`() {
		val listing = File(tmp.newFolder("out"), "output-metadata.json").apply { writeText("{\"elements\": [") }

		assertThat(ApkExport.resolve(listing, report(), tmp.root)).isEqualTo(ApkExport.Exportable.NotBuilt)
	}

	@Test
	fun `a standard build's APK is exported`() {
		val (listing, built) = built()

		assertThat(ApkExport.resolve(listing, report(), tmp.root)).isEqualTo(ApkExport.Exportable.Apk(built))
	}

	@Test
	fun `Quick Build's proxy APK at the same path is refused`() {
		val (listing, built) = built()
		val report = report(names = built, at = built.lastModified() + 1_000)

		assertThat(ApkExport.resolve(listing, report, tmp.root)).isEqualTo(ApkExport.Exportable.QuickBuildProxy)
	}

	@Test
	fun `a standard build after Quick Build's makes the APK exportable again`() {
		val (listing, built) = built()
		val report = report(names = built, at = built.lastModified() - 1_000)

		assertThat(ApkExport.resolve(listing, report, tmp.root)).isEqualTo(ApkExport.Exportable.Apk(built))
	}

	@Test
	fun `a copy that fails part-way leaves nothing under the export's name`() {
		val download = tmp.newFolder("Download")

		try {
			ApkExport.copyTo(apk, download, "app-debug") { _, to ->
				to.writeText("half an ap")
				throw IOException("disk full")
			}
		} catch (e: IOException) {
			// expected
		}

		assertThat(download.list()).isEmpty()
	}

	/** A variant's output directory holding `app-debug.apk` and its listing. */
	private fun built(): Pair<File, File> {
		val dir = tmp.newFolder("outputs", "apk", "debug")
		val apk = File(dir, "app-debug.apk").apply { writeText("apk") }
		val listing =
			File(dir, "output-metadata.json").apply {
				writeText(
					"""{"version":3,"artifactType":{"type":"APK","kind":"Directory"},"applicationId":"com.example.app",""" +
						""""variantName":"debug","elements":[{"type":"SINGLE","outputFile":"app-debug.apk"}],"elementType":"File"}""",
				)
			}
		return listing to apk
	}

	/** Quick Build's report, naming [names] as its proxy APK, written at [at]; absent without [names]. */
	private fun report(
		names: File? = null,
		at: Long = 0,
	): File {
		val report = File(tmp.root, "quickbuild/debug/setup.json")
		if (names != null) {
			report.parentFile.mkdirs()
			report.writeText("""{"proxyAppId":"com.example.app","entryActivity":null,"apk":"${names.absolutePath}"}""")
			report.setLastModified(at)
		}
		return report
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
