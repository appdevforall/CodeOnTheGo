package com.itsaky.androidide.assets.addons

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.manager.core.PluginBuildFiles
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class BundledAddonInstallerTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var pluginsDir: File
	private lateinit var templatesDir: File

	@Before
	fun setUp() {
		pluginsDir = File(tempFolder.root, "plugins")
		templatesDir = File(tempFolder.root, "templates")
	}

	private fun installer(
		readPluginId: (File) -> String? = {
			"com.example.plugin"
		},
	) = BundledAddonInstaller(pluginsDir, templatesDir, readPluginId)

	private fun bytes(text: String): InputStream = ByteArrayInputStream(text.toByteArray())

	private fun sha256(text: String): String =
		MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

	/** Delivers [first], then fails, like a copy cut short. */
	private fun failingAfter(first: String): InputStream =
		object : InputStream() {
			private val head = first.toByteArray()
			private var pos = 0

			override fun read(): Int = if (pos < head.size) head[pos++].toInt() else throw IOException("cut short")
		}

	@Test
	fun `a plugin is installed as pluginId-hash dot cgp`() {
		val installed = installer().install(BundledAddonKind.PLUGIN, "whatever.cgp", bytes("plugin bytes"))

		val expectedStem = PluginBuildFiles.fileStem("com.example.plugin", installed.file!!)
		assertThat(installed.file!!.name).isEqualTo("$expectedStem.cgp")
		assertThat(installed.file!!.name).startsWith("com.example.plugin-")
		assertThat(installed.file!!.parentFile).isEqualTo(pluginsDir)
		assertThat(installed.file!!.readText()).isEqualTo("plugin bytes")
		assertThat(installed.pluginId).isEqualTo("com.example.plugin")
		assertThat(pluginsDir.list()!!.toList()).containsExactly(installed.file!!.name)
	}

	@Test
	fun `the same plugin bytes always get the same name and another build another one`() {
		val first = installer().install(BundledAddonKind.PLUGIN, "a.cgp", bytes("one")).file!!.name
		val again = installer().install(BundledAddonKind.PLUGIN, "a.cgp", bytes("one")).file!!.name
		val other = installer().install(BundledAddonKind.PLUGIN, "a.cgp", bytes("two")).file!!.name

		assertThat(again).isEqualTo(first)
		assertThat(other).isNotEqualTo(first)
	}

	@Test
	fun `a template is installed under its bundled name`() {
		val installed = installer().install(BundledAddonKind.TEMPLATE, "starter.cgt", bytes("template bytes"))

		assertThat(installed.file!!).isEqualTo(File(templatesDir, "starter.cgt"))
		assertThat(installed.file!!.readText()).isEqualTo("template bytes")
		assertThat(installed.pluginId).isNull()
		assertThat(templatesDir.list()!!.toList()).containsExactly("starter.cgt")
	}

	@Test
	fun `an installed template replaces an older file of its name`() {
		templatesDir.mkdirs()
		File(templatesDir, "starter.cgt").writeText("old")

		installer().install(BundledAddonKind.TEMPLATE, "starter.cgt", bytes("new"))

		assertThat(File(templatesDir, "starter.cgt").readText()).isEqualTo("new")
		assertThat(templatesDir.list()!!.toList()).containsExactly("starter.cgt")
	}

	@Test
	fun `a plugin whose content matches its index hash is installed`() {
		val installed = installer().install(BundledAddonKind.PLUGIN, "a.cgp", bytes("plugin bytes"), sha256("plugin bytes"))

		assertThat(installed.failure).isNull()
		assertThat(installed.file!!.readText()).isEqualTo("plugin bytes")
	}

	@Test
	fun `a plugin whose content differs from its index hash is reported, not thrown, and leaves no file`() {
		var manifestRead = false
		val installed =
			installer(readPluginId = {
				manifestRead = true
				"com.example.plugin"
			}).install(BundledAddonKind.PLUGIN, "a.cgp", bytes("tampered"), sha256("plugin bytes"))

		assertThat(installed.file).isNull()
		assertThat(installed.pluginId).isNull()
		assertThat(installed.sourceName).isEqualTo("a.cgp")
		assertThat(installed.failure).contains("does not match the bundle index")
		assertThat(manifestRead).isFalse()
		assertThat(pluginsDir.list()!!.toList()).isEmpty()
	}

	@Test
	fun `a template whose content differs from its index hash is reported and keeps the old one`() {
		templatesDir.mkdirs()
		File(templatesDir, "starter.cgt").writeText("old")

		val installed = installer().install(BundledAddonKind.TEMPLATE, "starter.cgt", bytes("tampered"), sha256("new"))

		assertThat(installed.file).isNull()
		assertThat(installed.sourceName).isEqualTo("starter.cgt")
		assertThat(installed.failure).contains("does not match the bundle index")
		assertThat(templatesDir.list()!!.toList()).containsExactly("starter.cgt")
		assertThat(File(templatesDir, "starter.cgt").readText()).isEqualTo("old")
	}

	@Test
	fun `a template whose content matches its index hash replaces the old one`() {
		templatesDir.mkdirs()
		File(templatesDir, "starter.cgt").writeText("old")

		val installed = installer().install(BundledAddonKind.TEMPLATE, "starter.cgt", bytes("new"), sha256("new"))

		assertThat(installed.failure).isNull()
		assertThat(File(templatesDir, "starter.cgt").readText()).isEqualTo("new")
	}

	@Test
	fun `a plugin copy that fails leaves no file behind`() {
		assertThrows(IOException::class.java) {
			installer().install(BundledAddonKind.PLUGIN, "a.cgp", failingAfter("truncated"))
		}

		assertThat(pluginsDir.list()!!.toList()).isEmpty()
	}

	@Test
	fun `a template copy that fails leaves no partial file and keeps the old one`() {
		templatesDir.mkdirs()
		File(templatesDir, "starter.cgt").writeText("old")

		assertThrows(IOException::class.java) {
			installer().install(BundledAddonKind.TEMPLATE, "starter.cgt", failingAfter("truncated"))
		}

		assertThat(templatesDir.list()!!.toList()).containsExactly("starter.cgt")
		assertThat(File(templatesDir, "starter.cgt").readText()).isEqualTo("old")
	}

	@Test
	fun `a plugin without a readable manifest is reported, not thrown, and leaves no file`() {
		val result = installer(readPluginId = { null }).install(BundledAddonKind.PLUGIN, "a.cgp", bytes("not a plugin"))

		assertThat(result.file).isNull()
		assertThat(result.pluginId).isNull()
		assertThat(result.sourceName).isEqualTo("a.cgp")
		assertThat(result.failure).isNotNull()
		assertThat(pluginsDir.list()!!.toList()).isEmpty()
	}

	@Test
	fun `a plugin whose manifest reader throws is reported, not thrown`() {
		val result =
			installer(readPluginId = { throw IllegalStateException("bad zip") })
				.install(BundledAddonKind.PLUGIN, "a.cgp", bytes("not a plugin"))

		assertThat(result.failure).isNotNull()
		assertThat(pluginsDir.list()!!.toList()).isEmpty()
	}

	@Test
	fun `a plugin whose ID is a path is reported, not thrown, and leaves no file`() {
		val result = installer(readPluginId = { "../escape" }).install(BundledAddonKind.PLUGIN, "a.cgp", bytes("plugin"))

		assertThat(result.file).isNull()
		assertThat(result.failure).contains("../escape")
		assertThat(pluginsDir.list()!!.toList()).isEmpty()
	}

	@Test
	fun `a plugin whose ID is too long for a file name is reported, not thrown, and leaves no file`() {
		val result =
			installer(readPluginId = { "a".repeat(300) }).install(BundledAddonKind.PLUGIN, "a.cgp", bytes("plugin"))

		assertThat(result.file).isNull()
		assertThat(result.failure).contains("too long")
		assertThat(pluginsDir.list()!!.toList()).isEmpty()
	}
}
