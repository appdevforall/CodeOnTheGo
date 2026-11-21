package com.itsaky.androidide.assets.addons

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.assets.AssetsInstallationHelper
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_DISTRIBUTION_ARCHIVE_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.adfa.constants.TEMPLATE_CORE_ARCHIVE
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BundledAddonsTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private class FakeSource(
		private val files: Map<String, Map<String, Long>>,
	) : BundleSource {
		override fun names(dir: String) = files[dir]?.keys?.toList().orEmpty()

		override fun size(
			dir: String,
			name: String,
		) = files.getValue(dir).getValue(name)

		override fun open(
			dir: String,
			name: String,
		): InputStream = ByteArrayInputStream(ByteArray(0))
	}

	private fun source(
		plugins: Map<String, Long> = emptyMap(),
		templates: Map<String, Long> = emptyMap(),
	) = FakeSource(mapOf("plugins" to plugins, "templates" to templates))

	@Test
	fun `a thin bundle lists nothing`() {
		assertThat(BundledAddons.list(source())).isEmpty()
	}

	@Test
	fun `a thin build installs exactly the eight fixed entries`() {
		assertThat(AssetsInstallationHelper.entriesToInstall(emptyList()))
			.containsExactly(
				GRADLE_DISTRIBUTION_ARCHIVE_NAME,
				ANDROID_SDK_ZIP,
				DOCUMENTATION_DB,
				LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
				"bootstrap.zip",
				GRADLE_API_NAME_JAR_ZIP,
				"plugin-artifacts.zip",
				TEMPLATE_CORE_ARCHIVE,
			).inOrder()
	}

	@Test
	fun `a fat build installs the fixed entries then each bundled file`() {
		val bundled = BundledAddons.list(source(plugins = mapOf("a.cgp" to 5L), templates = mapOf("t.cgt" to 7L)))

		val entries = AssetsInstallationHelper.entriesToInstall(bundled)

		assertThat(entries).hasSize(10)
		assertThat(entries.take(8)).containsExactlyElementsIn(AssetsInstallationHelper.entriesToInstall(emptyList())).inOrder()
		assertThat(entries.takeLast(2)).containsExactly("plugins/a.cgp", "templates/t.cgt").inOrder()
	}

	@Test
	fun `some entries carry their size and sort by name, plugins first`() {
		val entries =
			BundledAddons.list(
				source(
					plugins = mapOf("b.cgp" to 20L, "a.cgp" to 10L),
					templates = mapOf("t.cgt" to 30L),
				),
			)

		assertThat(entries)
			.containsExactly(
				BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 10L),
				BundledEntry(BundledAddonKind.PLUGIN, "b.cgp", 20L),
				BundledEntry(BundledAddonKind.TEMPLATE, "t.cgt", 30L),
			).inOrder()
		assertThat(entries.map { it.entryName }).containsExactly("plugins/a.cgp", "plugins/b.cgp", "templates/t.cgt").inOrder()
	}

	@Test
	fun `many entries are all listed`() {
		val plugins = (1..200).associate { "p$it.cgp" to it.toLong() }
		val templates = (1..150).associate { "t$it.cgt" to it.toLong() }

		assertThat(BundledAddons.list(source(plugins, templates))).hasSize(350)
	}

	@Test
	fun `files that are not add-ons of their folder are ignored`() {
		val entries =
			BundledAddons.list(
				source(
					plugins = mapOf("a.cgp" to 1L, "readme.txt" to 2L, "t.cgt" to 3L, ".hidden.cgp" to 4L, "sub/b.cgp" to 5L, "UPPER.CGP" to 6L),
					templates = mapOf("t.cgt" to 7L, "a.cgp" to 8L),
				),
			)

		assertThat(entries.map { it.entryName }).containsExactly("plugins/UPPER.CGP", "plugins/a.cgp", "templates/t.cgt").inOrder()
	}

	@Test
	fun `parse recognizes only the names a listing produces`() {
		assertThat(BundledAddons.parse("plugins/a.cgp")).isEqualTo(BundledAddonKind.PLUGIN to "a.cgp")
		assertThat(BundledAddons.parse("templates/t.cgt")).isEqualTo(BundledAddonKind.TEMPLATE to "t.cgt")
		assertThat(BundledAddons.parse("plugins/t.cgt")).isNull()
		assertThat(BundledAddons.parse("plugins/../a.cgp")).isNull()
		assertThat(BundledAddons.parse("core.cgt")).isNull()
		assertThat(BundledAddons.parse("plugin-artifacts.zip")).isNull()
	}

	@Test
	fun `the fingerprint is stable for the same bundle in any order`() {
		val a = BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 10L)
		val t = BundledEntry(BundledAddonKind.TEMPLATE, "t.cgt", 30L)

		assertThat(BundledAddons.fingerprint(listOf(a, t))).isEqualTo(BundledAddons.fingerprint(listOf(t, a)))
		assertThat(BundledAddons.fingerprint(listOf(a, t))).isEqualTo(BundledAddons.fingerprint(listOf(a, t)))
	}

	@Test
	fun `the fingerprint changes with a name, a size, a kind or an entry`() {
		val a = BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 10L)
		val base = BundledAddons.fingerprint(listOf(a))

		assertThat(BundledAddons.fingerprint(listOf(a.copy(fileName = "b.cgp")))).isNotEqualTo(base)
		assertThat(BundledAddons.fingerprint(listOf(a.copy(size = 11L)))).isNotEqualTo(base)
		assertThat(BundledAddons.fingerprint(listOf(a.copy(kind = BundledAddonKind.TEMPLATE, fileName = "a.cgt")))).isNotEqualTo(base)
		assertThat(BundledAddons.fingerprint(listOf(a, BundledEntry(BundledAddonKind.TEMPLATE, "t.cgt", 1L)))).isNotEqualTo(base)
		assertThat(BundledAddons.fingerprint(emptyList())).isNotEqualTo(base)
	}

	@Test
	fun `the empty bundle has a fingerprint`() {
		assertThat(BundledAddons.fingerprint(emptyList())).matches("[0-9a-f]{64}")
	}

	@Test
	fun `a zip source lists only the direct files of a folder, with their sizes`() {
		val zipFile = tempFolder.newFile("assets.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			listOf("bootstrap.zip" to 3, "plugins/a.cgp" to 5, "plugins/nested/b.cgp" to 9, "templates/t.cgt" to 7).forEach { (name, size) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(ByteArray(size))
				zip.closeEntry()
			}
		}

		ZipFile(zipFile).use { zip ->
			val entries = BundledAddons.list(ZipBundleSource(zip))

			assertThat(entries)
				.containsExactly(
					BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 5L),
					BundledEntry(BundledAddonKind.TEMPLATE, "t.cgt", 7L),
				).inOrder()
			assertThat(ZipBundleSource(zip).open("plugins", "a.cgp").use { it.readBytes() }).hasLength(5)
		}
	}

	@Test
	fun `a zip source without the folders lists nothing`() {
		val zipFile = tempFolder.newFile("thin.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("bootstrap.zip"))
			zip.closeEntry()
		}

		ZipFile(zipFile).use { assertThat(BundledAddons.list(ZipBundleSource(it))).isEmpty() }
	}
}
