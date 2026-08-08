package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.PluginSettingsEntry
import com.itsaky.androidide.plugins.extensions.SettingsExtension
import org.junit.Test

/**
 * Covers the collection contract behind `PluginManager.getPluginSettingsEntries()`: rows are paired
 * with their owning plugin, ordered, filtered by visibility, and one broken plugin cannot take the
 * Preferences screen down with it.
 */
class PluginSettingsEntriesTest {
	@Test
	fun entriesArePairedWithTheirPluginAndSortedByOrderThenTitle() {
		val entries =
			collectPluginSettingsEntries(
				listOf(
					"plugin.b" to FakeSettingsExtension(entry("zeta", order = 0), entry("alpha", order = 0)),
					"plugin.a" to FakeSettingsExtension(entry("first", order = -1)),
				),
				onError = ::failOnError,
			)

		assertThat(entries.map { it.first to it.second.id })
			.containsExactly(
				"plugin.a" to "first",
				"plugin.b" to "alpha",
				"plugin.b" to "zeta",
			).inOrder()
	}

	@Test
	fun aThrowingExtensionIsSkippedAndReportedWhileOthersStillContribute() {
		val failures = mutableListOf<String>()

		val entries =
			collectPluginSettingsEntries(
				listOf(
					"plugin.broken" to ThrowingSettingsExtension,
					"plugin.ok" to FakeSettingsExtension(entry("agent")),
				),
				onError = { pluginId, _ -> failures.add(pluginId) },
			)

		assertThat(entries.map { it.second.id }).containsExactly("agent")
		assertThat(failures).containsExactly("plugin.broken")
	}

	@Test
	fun invisibleEntriesAreDropped() {
		val entries =
			collectPluginSettingsEntries(
				listOf(
					"plugin.a" to
						FakeSettingsExtension(
							entry("hidden").copy(isVisible = false),
							entry("shown"),
						),
				),
				onError = ::failOnError,
			)

		assertThat(entries.map { it.second.id }).containsExactly("shown")
	}

	// PreferencesActivity compares successive results to decide whether to rebuild the tree, so two
	// entries that tie on order and title must still come back in the same order every time.
	@Test
	fun orderIsTotalWhenTwoPluginsTieOnOrderAndTitle() {
		val extensions =
			listOf(
				"plugin.z" to FakeSettingsExtension(entry("agent")),
				"plugin.a" to FakeSettingsExtension(entry("agent")),
			)

		val forward = collectPluginSettingsEntries(extensions, onError = ::failOnError)
		val reversed = collectPluginSettingsEntries(extensions.reversed(), onError = ::failOnError)

		assertThat(forward.map { it.first }).containsExactly("plugin.a", "plugin.z").inOrder()
		assertThat(reversed).isEqualTo(forward)
	}

	@Test
	fun noExtensionsContributeNoRows() {
		assertThat(collectPluginSettingsEntries(emptyList(), onError = ::failOnError)).isEmpty()
	}

	private fun entry(
		id: String,
		order: Int = 0,
	) = PluginSettingsEntry(
		id = id,
		title = id,
		fragmentClassName = "com.example.${id}Fragment",
		order = order,
	)

	private fun failOnError(
		pluginId: String,
		error: Throwable,
	): Unit = throw AssertionError("unexpected failure for $pluginId", error)

	private open class NoOpPlugin : SettingsExtension {
		override fun initialize(context: PluginContext) = true

		override fun activate() = true

		override fun deactivate() = true

		override fun dispose() = Unit
	}

	private class FakeSettingsExtension(
		private vararg val entries: PluginSettingsEntry,
	) : NoOpPlugin() {
		override fun getSettingsEntries(): List<PluginSettingsEntry> = entries.toList()
	}

	private object ThrowingSettingsExtension : NoOpPlugin() {
		override fun getSettingsEntries(): List<PluginSettingsEntry> = throw IllegalStateException("boom")
	}
}
