package com.itsaky.androidide.assets.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
		private val indexText: String? = null,
	) : BundleSource {
		override fun index() = indexText

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
		index: String? = null,
	) = FakeSource(mapOf("plugins" to plugins, "templates" to templates), index)

	/** A source with only an index: asking it for a folder, a size or a file fails the test. */
	private class IndexOnlySource(
		private val indexText: String,
	) : BundleSource {
		override fun names(dir: String): List<String> = throw AssertionError("listed $dir")

		override fun size(
			dir: String,
			name: String,
		): Long = throw AssertionError("sized $dir/$name")

		override fun open(
			dir: String,
			name: String,
		): InputStream = throw AssertionError("opened $dir/$name")

		override fun index() = indexText
	}

	private val shaA = "a".repeat(64)
	private val shaB = "b".repeat(64)
	private val shaT = "c".repeat(64)

	private fun line(
		entryName: String,
		size: Long,
		sha256: String,
	) = "$entryName\t$size\t$sha256\n"

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
					plugins =
						mapOf(
							"a.cgp" to 1L,
							"readme.txt" to 2L,
							"t.cgt" to 3L,
							".hidden.cgp" to 4L,
							"sub/b.cgp" to 5L,
							"UPPER.CGP" to 6L,
						),
					templates = mapOf("t.cgt" to 7L, "a.cgp" to 8L),
				),
			)

		assertThat(entries.map { it.entryName }).containsExactly("plugins/UPPER.CGP", "plugins/a.cgp", "templates/t.cgt").inOrder()
	}

	@Test
	fun `a bundled template named like the core template is ignored`() {
		val templates = mapOf("core.cgt" to 1L, "Core.CGT" to 2L, "t.cgt" to 3L)
		val entries = BundledAddons.list(source(templates = templates))

		assertThat(entries.map { it.entryName }).containsExactly("templates/t.cgt")
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
			val entries = listOf("bootstrap.zip" to 3, "plugins/a.cgp" to 5, "plugins/nested/b.cgp" to 9, "templates/t.cgt" to 7)
			entries.forEach { (name, size) ->
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
	fun `an index lists the bundle without listing, sizing or opening an add-on`() {
		val index = line("plugins/b.cgp", 20L, shaB) + line("templates/t.cgt", 30L, shaT) + line("plugins/a.cgp", 10L, shaA)

		assertThat(BundledAddons.list(IndexOnlySource(index)))
			.containsExactly(
				BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 10L, shaA),
				BundledEntry(BundledAddonKind.PLUGIN, "b.cgp", 20L, shaB),
				BundledEntry(BundledAddonKind.TEMPLATE, "t.cgt", 30L, shaT),
			).inOrder()
	}

	@Test
	fun `an empty index is a thin bundle`() {
		assertThat(BundledAddons.list(IndexOnlySource(""))).isEmpty()
		assertThat(BundledAddons.list(IndexOnlySource("\n"))).isEmpty()
	}

	@Test
	fun `the fingerprint notices new content at the same name and size`() {
		val old = BundledAddons.list(IndexOnlySource(line("plugins/a.cgp", 10L, shaA)))
		val rebuilt = BundledAddons.list(IndexOnlySource(line("plugins/a.cgp", 10L, shaB)))

		assertThat(BundledAddons.fingerprint(rebuilt)).isNotEqualTo(BundledAddons.fingerprint(old))
		assertThat(BundledAddons.fingerprint(old)).isNotEqualTo(BundledAddons.fingerprint(emptyList()))
	}

	@Test
	fun `a malformed index leaves the folders to decide`() {
		val folders = listOf(BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 10L))
		val malformed =
			listOf(
				"plugins/a.cgp\t10\n",
				"plugins/a.cgp\t10\t$shaA\textra\n",
				"plugins/a.cgp\tten\t$shaA\n",
				"plugins/a.cgp\t-1\t$shaA\n",
				"plugins/a.cgp\t10\t${shaA.uppercase()}\n",
				"plugins/a.cgp\t10\t${shaA.drop(1)}\n",
				line("other/a.cgp", 10L, shaA),
				line("plugins/nested/a.cgp", 10L, shaA),
				line("plugins/.hidden.cgp", 10L, shaA),
				line("templates/$TEMPLATE_CORE_ARCHIVE", 10L, shaA),
				line("plugins/a.cgp", 10L, shaA) + line("plugins/a.cgp", 10L, shaB),
			)

		malformed.forEach { index ->
			assertWithMessage(index).that(BundledAddons.list(source(plugins = mapOf("a.cgp" to 10L), index = index))).isEqualTo(folders)
		}
	}

	@Test
	fun `a bundle without an index is listed from its folders, without hashes`() {
		val entries = BundledAddons.list(source(plugins = mapOf("a.cgp" to 10L)))

		assertThat(entries).containsExactly(BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 10L))
		assertThat(entries.single().sha256).isNull()
	}

	@Test
	fun `a zip source reads its index, and has none without the entry`() {
		val indexed = tempFolder.newFile("indexed.zip")
		ZipOutputStream(indexed.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("plugins/a.cgp"))
			zip.write(ByteArray(5))
			zip.closeEntry()
			zip.putNextEntry(ZipEntry(BundledAddons.INDEX_FILE))
			zip.write(line("plugins/a.cgp", 5L, shaA).toByteArray())
			zip.closeEntry()
		}
		val plain = tempFolder.newFile("plain.zip")
		ZipOutputStream(plain.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("plugins/a.cgp"))
			zip.write(ByteArray(5))
			zip.closeEntry()
		}

		ZipFile(indexed).use { zip ->
			assertThat(BundledAddons.list(ZipBundleSource(zip))).containsExactly(BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 5L, shaA))
		}
		ZipFile(plain).use { zip ->
			assertThat(ZipBundleSource(zip).index()).isNull()
			assertThat(BundledAddons.list(ZipBundleSource(zip))).containsExactly(BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 5L))
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
