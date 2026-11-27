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
		signatureOf: (File) -> ByteArray? = { OUR_KEY },
	) = BundledAddonInstaller(pluginsDir, templatesDir, readPluginId, signatureOf)

	/** An installed build of `com.example.plugin` under another name, as a user's install leaves it. */
	private fun installedBuild(text: String = "user build"): File {
		pluginsDir.mkdirs()
		return File(pluginsDir, "com.example.plugin-user.cgp").apply { writeText(text) }
	}

	/** Signs a file by its content: the bytes "theirs" or "user build (theirs)" carry another key. */
	private val signedByContent: (File) -> ByteArray? = { file ->
		if (file.readText().contains("theirs")) THEIR_KEY else OUR_KEY
	}

	private companion object {
		val OUR_KEY = "our key".toByteArray()
		val THEIR_KEY = "their key".toByteArray()
	}

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
		assertThat(result.failure).isEqualTo(BundledAddonInstaller.UNREADABLE_MANIFEST)
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

	@Test
	fun `a plugin replaces an installed build of its ID signed with the same key`() {
		val old = installedBuild()

		val installed = installer(signatureOf = signedByContent).install(BundledAddonKind.PLUGIN, "a.cgp", bytes("bundled build"))

		assertThat(installed.failure).isNull()
		assertThat(old.exists()).isFalse()
		assertThat(pluginsDir.list()!!.toList()).containsExactly(installed.file!!.name)
	}

	@Test
	fun `a plugin whose ID is installed signed with another key is reported, and the installed build stays`() {
		val old = installedBuild("user build (theirs)")

		val installed = installer(signatureOf = signedByContent).install(BundledAddonKind.PLUGIN, "a.cgp", bytes("bundled build"))

		assertThat(installed.file).isNull()
		assertThat(installed.pluginId).isNull()
		assertThat(installed.failure).isEqualTo(BundledAddonInstaller.SIGNED_BY_ANOTHER_KEY)
		assertThat(installed.sourceName).isEqualTo("a.cgp")
		assertThat(old.readText()).isEqualTo("user build (theirs)")
		assertThat(pluginsDir.list()!!.toList()).containsExactly(old.name)
	}

	@Test
	fun `a plugin is refused when only one of it and the installed build is signed, and the installed build stays`() {
		val old = installedBuild()

		val noIncoming =
			installer(signatureOf = { if (it == old) OUR_KEY else null })
				.install(BundledAddonKind.PLUGIN, "a.cgp", bytes("bundled build"))
		val noInstalled =
			installer(signatureOf = { if (it == old) null else OUR_KEY })
				.install(BundledAddonKind.PLUGIN, "a.cgp", bytes("bundled build"))

		assertThat(noIncoming.failure).isEqualTo(BundledAddonInstaller.ONE_UNSIGNED)
		assertThat(noInstalled.failure).isEqualTo(BundledAddonInstaller.ONE_UNSIGNED)
		assertThat(pluginsDir.list()!!.toList()).containsExactly(old.name)
	}

	@Test
	fun `another plugin ID's build is left alone and not signature-checked`() {
		val other = installedBuild("theirs")

		val installed =
			installer(
				readPluginId = { if (it == other) "com.example.other" else "com.example.plugin" },
				signatureOf = signedByContent,
			).install(BundledAddonKind.PLUGIN, "a.cgp", bytes("bundled build"))

		assertThat(installed.failure).isNull()
		assertThat(other.exists()).isTrue()
		assertThat(pluginsDir.list()!!.toList()).containsExactly(other.name, installed.file!!.name)
	}

	@Test
	fun `a build that stays after removal fails the install with an IO error`() {
		val old = installedBuild()

		assertThrows(IOException::class.java) {
			BundledAddonInstaller(pluginsDir, templatesDir, { "com.example.plugin" }, { OUR_KEY }, removeOtherBuilds = { _, _ -> })
				.install(BundledAddonKind.PLUGIN, "a.cgp", bytes("bundled build"))
		}
		assertThat(old.exists()).isTrue()
		assertThat(pluginsDir.list()!!.toList()).containsExactly(old.name)
	}

	@Test
	fun `an unsigned plugin replaces an unsigned installed build of its ID`() {
		// The plugins the addons repository builds carry no signature at all.
		val old = installedBuild()

		val installed = installer(signatureOf = { null }).install(BundledAddonKind.PLUGIN, "a.cgp", bytes("bundled build"))

		assertThat(installed.failure).isNull()
		assertThat(old.exists()).isFalse()
		assertThat(pluginsDir.list()!!.toList()).containsExactly(installed.file!!.name)
	}

	@Test
	fun `a build an earlier bundle installed is replaced whatever its signature`() {
		val old = installedBuild("user build (theirs)")

		val installed =
			BundledAddonInstaller(pluginsDir, templatesDir, { "com.example.plugin" }, signedByContent, bundledBefore = setOf("com.example.plugin"))
				.install(BundledAddonKind.PLUGIN, "a.cgp", bytes("bundled build"))

		assertThat(installed.failure).isNull()
		assertThat(old.exists()).isFalse()
		assertThat(pluginsDir.list()!!.toList()).containsExactly(installed.file!!.name)
	}
}
