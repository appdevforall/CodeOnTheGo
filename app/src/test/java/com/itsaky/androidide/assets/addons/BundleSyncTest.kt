package com.itsaky.androidide.assets.addons

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class BundleSyncTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var pluginsDir: File
	private lateinit var templatesDir: File
	private lateinit var record: BundledAddonsRecord
	private val uninstalled = mutableListOf<String>()

	/** IDs whose files the fake uninstall cannot delete, like a failed delete on the device. */
	private val undeletable = mutableSetOf<String>()

	@Before
	fun setUp() {
		pluginsDir = tempFolder.newFolder("plugins")
		templatesDir = tempFolder.newFolder("templates")
		record = BundledAddonsRecord(File(tempFolder.root, BundledAddonsRecord.FILE_NAME))
	}

	/** A fake plugin file's content: its ID, then a build marker. */
	private fun plugin(
		id: String,
		build: String = "1",
	) = "$id|$build"

	private fun idOf(file: File): String? = file.readText().substringBefore('|').takeIf { it.isNotEmpty() && '|' in file.readText() }

	/** Deletes every plugin file of [pluginId], as PluginManager.uninstallPlugin does. */
	private fun uninstall(pluginId: String) {
		uninstalled += pluginId
		if (pluginId in undeletable) return
		pluginsDir.listFiles { f -> f.name.endsWith(".cgp") && idOf(f) == pluginId }?.forEach { it.delete() }
	}

	private fun sync(): BundleSync =
		BundleSync(
			record = record,
			pluginsDir = pluginsDir,
			templatesDir = templatesDir,
			readPluginId = ::idOf,
			uninstallPlugin = ::uninstall,
		)

	private class FakeSource(
		val plugins: Map<String, String> = emptyMap(),
		val templates: Map<String, String> = emptyMap(),
		val failOn: String? = null,
	) : BundleSource {
		private fun files(dir: String) = if (dir == BundledAddonKind.PLUGIN.dir) plugins else templates

		override fun names(dir: String) = files(dir).keys.toList()

		override fun size(
			dir: String,
			name: String,
		) = files(dir).getValue(name).length.toLong()

		override fun open(
			dir: String,
			name: String,
		): InputStream {
			if (name == failOn) throw IOException("disk full")
			return ByteArrayInputStream(files(dir).getValue(name).toByteArray())
		}
	}

	private fun run(source: FakeSource): BundledAddonsState = sync().sync(source, BundledAddons.list(source))

	private fun installedPluginIds() = pluginsDir.listFiles { f -> f.name.endsWith(".cgp") }!!.mapNotNull(::idOf).sorted()

	private fun templateNames() = templatesDir.list()!!.sorted()

	/** A user's plugin, installed from the Add-ons Manager. */
	private fun userPlugin(
		id: String,
		build: String = "user",
	) = File(pluginsDir, "$id-user.cgp").apply { writeText(plugin(id, build)) }

	private fun userTemplate(name: String) = File(templatesDir, name).apply { writeText("user template") }

	private val bundleA =
		FakeSource(
			plugins = mapOf("p1.cgp" to plugin("com.p1"), "p2.cgp" to plugin("com.p2")),
			templates = mapOf("t1.cgt" to "t1"),
		)
	private val bundleB =
		FakeSource(
			plugins = mapOf("p2.cgp" to plugin("com.p2", "2"), "p3.cgp" to plugin("com.p3")),
			templates = mapOf("t2.cgt" to "t2"),
		)

	@Test
	fun `a missing record needs a sync and the sync installs the whole bundle`() {
		assertThat(sync().isNeeded(BundledAddons.list(bundleA))).isTrue()

		val state = run(bundleA)

		assertThat(installedPluginIds()).containsExactly("com.p1", "com.p2")
		assertThat(templateNames()).containsExactly("t1.cgt")
		assertThat(state.pluginIds).containsExactly("com.p1", "com.p2").inOrder()
		assertThat(record.read()).isEqualTo(state)
	}

	@Test
	fun `an unchanged bundle needs no sync`() {
		run(bundleA)

		assertThat(sync().isNeeded(BundledAddons.list(bundleA))).isFalse()
		assertThat(sync().isNeeded(BundledAddons.list(bundleB))).isTrue()
	}

	@Test
	fun `a new bundle replaces the old one and keeps user add-ons`() {
		run(bundleA)
		userPlugin("com.u1")
		userTemplate("ut1.cgt")

		val state = run(bundleB)

		assertThat(installedPluginIds()).containsExactly("com.p2", "com.p3", "com.u1")
		assertThat(templateNames()).containsExactly("t2.cgt", "ut1.cgt")
		assertThat(state.pluginIds).containsExactly("com.p2", "com.p3")
		assertThat(state.templateFiles).containsExactly("t2.cgt")
	}

	@Test
	fun `the new build of a bundled plugin replaces the old build`() {
		run(bundleA)

		run(bundleB)

		val p2 = pluginsDir.listFiles { f -> idOf(f) == "com.p2" }!!
		assertThat(p2).hasLength(1)
		assertThat(p2.single().readText()).isEqualTo(plugin("com.p2", "2"))
	}

	@Test
	fun `a thin APK over a fat installation removes every bundled add-on`() {
		run(bundleA)
		userPlugin("com.u1")
		userTemplate("ut1.cgt")

		val state = run(FakeSource())

		assertThat(installedPluginIds()).containsExactly("com.u1")
		assertThat(templateNames()).containsExactly("ut1.cgt")
		assertThat(state.pluginIds).isEmpty()
		assertThat(state.templateFiles).isEmpty()
	}

	@Test
	fun `a user plugin with a bundled ID is overwritten`() {
		run(bundleA)
		pluginsDir.listFiles { f -> idOf(f) == "com.p2" }!!.forEach { it.delete() }
		userPlugin("com.p2", "mine")

		run(bundleB)

		val p2 = pluginsDir.listFiles { f -> idOf(f) == "com.p2" }!!
		assertThat(p2.map { it.readText() }).containsExactly(plugin("com.p2", "2"))
	}

	@Test
	fun `a user template with a bundled name is overwritten`() {
		userTemplate("t1.cgt")

		run(bundleA)

		assertThat(File(templatesDir, "t1.cgt").readText()).isEqualTo("t1")
	}

	@Test
	fun `a bundled add-on the user removed comes back with a new bundle`() {
		run(bundleA)
		uninstall("com.p2")
		File(templatesDir, "t1.cgt").delete()
		val bundleA2 = FakeSource(plugins = bundleA.plugins + ("p4.cgp" to plugin("com.p4")), templates = bundleA.templates)

		run(bundleA2)

		assertThat(installedPluginIds()).containsExactly("com.p1", "com.p2", "com.p4")
		assertThat(templateNames()).containsExactly("t1.cgt")
	}

	@Test
	fun `a plugin with no readable ID is recorded as a failure and the rest install`() {
		val source = FakeSource(plugins = mapOf("bad.cgp" to "no id here", "p1.cgp" to plugin("com.p1")))

		val state = run(source)

		assertThat(installedPluginIds()).containsExactly("com.p1")
		assertThat(state.failures.map { it.pluginId }).containsExactly("bad.cgp")
		assertThat(state.failures.single().reported).isFalse()
	}

	@Test
	fun `a sync cut short keeps the old record so it runs again`() {
		val before = run(bundleA)
		val failing = FakeSource(plugins = bundleB.plugins, templates = bundleB.templates, failOn = "p3.cgp")

		assertThrows(IOException::class.java) { run(failing) }

		assertThat(record.read()).isEqualTo(before)
		assertThat(sync().isNeeded(BundledAddons.list(bundleB))).isTrue()
	}

	@Test
	fun `a plugin whose build did not change is not uninstalled, so it keeps its state`() {
		run(bundleA)
		val p1Before = pluginsDir.listFiles { f -> idOf(f) == "com.p1" }!!.single()
		uninstalled.clear()

		run(FakeSource(plugins = bundleA.plugins, templates = bundleA.templates + ("t9.cgt" to "t9")))

		assertThat(uninstalled).isEmpty()
		assertThat(pluginsDir.listFiles { f -> idOf(f) == "com.p1" }!!.map { it.name }).containsExactly(p1Before.name)
	}

	@Test
	fun `a changed build is installed through uninstall, which clears the old one's state`() {
		run(bundleA)
		uninstalled.clear()

		run(bundleB)

		// com.p2 changed build; com.p1 left the bundle.
		assertThat(uninstalled).containsExactly("com.p2", "com.p1")
	}

	@Test
	fun `an older build that cannot be deleted fails the sync and keeps the old record`() {
		val before = run(bundleA)
		undeletable += "com.p2"

		assertThrows(IOException::class.java) { run(bundleB) }

		assertThat(record.read()).isEqualTo(before)
		// The new build never landed beside the old one.
		assertThat(pluginsDir.listFiles { f -> idOf(f) == "com.p2" }!!.map { it.readText() }).containsExactly(plugin("com.p2"))
	}

	@Test
	fun `a sync cut short converges at the next run`() {
		run(bundleA)
		assertThrows(IOException::class.java) {
			run(FakeSource(plugins = bundleB.plugins, templates = bundleB.templates, failOn = "p3.cgp"))
		}

		val state = run(bundleB)

		assertThat(installedPluginIds()).containsExactly("com.p2", "com.p3")
		assertThat(templateNames()).containsExactly("t2.cgt")
		assertThat(record.read()).isEqualTo(state)
	}

	@Test
	fun `a missing record over user add-ons removes nothing of theirs`() {
		userPlugin("com.u1")
		userTemplate("ut1.cgt")

		run(bundleA)

		assertThat(installedPluginIds()).containsExactly("com.p1", "com.p2", "com.u1")
		assertThat(templateNames()).containsExactly("t1.cgt", "ut1.cgt")
		assertThat(uninstalled).isEmpty()
	}

	@Test
	fun `a debug build whose assets zip is gone does not sync at all`() {
		var ran = false

		val result =
			BundleSyncAtStart.withBundleSource(
				release = false,
				splitZip = File(tempFolder.root, "assets-arm64-v8a.zip"),
				assets = { error("a debug build reads no APK assets") },
			) { ran = true }

		assertThat(result).isNull()
		assertThat(ran).isFalse()
	}

	@Test
	fun `a release build reads the APK assets`() {
		val source = FakeSource()

		val result = BundleSyncAtStart.withBundleSource(true, File(tempFolder.root, "absent.zip"), { source }) { it }

		assertThat(result).isSameInstanceAs(source)
	}

	@Test
	fun `a rejected copy keeps the old bundled add-ons instead of removing them`() {
		run(bundleA)
		val corrupted = FakeSource(plugins = mapOf("p1.cgp" to "no id here", "p2.cgp" to plugin("com.p2")), templates = mapOf())

		val state = run(corrupted)

		assertThat(installedPluginIds()).containsExactly("com.p1", "com.p2")
		assertThat(templateNames()).containsExactly("t1.cgt")
		assertThat(state.failures.map { it.pluginId }).containsExactly("p1.cgp")
	}

	@Test
	fun `a copy that does not match the index is rejected and the installed build kept`() {
		run(bundleA)
		val source =
			FakeSource(
				plugins = mapOf("p1.cgp" to plugin("com.p1", "2"), "p2.cgp" to plugin("com.p2")),
				templates = bundleA.templates,
			)
		val entries = BundledAddons.list(source).map { if (it.fileName == "p1.cgp") it.copy(sha256 = "0".repeat(64)) else it }

		val state = sync().sync(source, entries)

		assertThat(pluginsDir.listFiles { f -> idOf(f) == "com.p1" }!!.map { it.readText() }).containsExactly(plugin("com.p1"))
		assertThat(state.failures.map { it.pluginId }).containsExactly("p1.cgp")
		assertThat(uninstalled).doesNotContain("com.p1")
	}

	@Test
	fun `old failures are dropped with the old bundle`() {
		run(FakeSource(plugins = mapOf("bad.cgp" to "no id here")))

		val state = run(bundleA)

		assertThat(state.failures).isEmpty()
	}
}
