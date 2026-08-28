/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.preferences

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.extensions.PluginSettingsEntry
import com.itsaky.androidide.plugins.manager.core.PluginManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class PluginPrefExtsTest {
	@Test
	fun `maps every unique entry to a namespaced preference row`() {
		val pluginManager =
			fakePluginManager(
				"com.example.ai" to
					PluginSettingsEntry(id = "agent", title = "Agent", summary = "Configure it", fragmentClassName = "com.example.ai.Frag"),
				"com.example.git" to PluginSettingsEntry(id = "sync", title = "Sync", fragmentClassName = "com.example.git.Frag"),
			)

		val rows = pluginSettingsPreferences(pluginManager)

		assertThat(rows).containsExactly(
			PluginSettingsEntryPreference(
				pluginId = "com.example.ai",
				entryId = "agent",
				titleText = "Agent",
				summaryText = "Configure it",
				fragmentClassName = "com.example.ai.Frag",
			),
			PluginSettingsEntryPreference(
				pluginId = "com.example.git",
				entryId = "sync",
				titleText = "Sync",
				summaryText = null,
				fragmentClassName = "com.example.git.Frag",
			),
		)
	}

	@Test
	fun `drops a later entry that shares a key with an earlier one, keeping the first`() {
		val pluginManager =
			fakePluginManager(
				"com.example.ai" to PluginSettingsEntry(id = "agent", title = "First", fragmentClassName = "com.example.ai.First"),
				"com.example.ai" to PluginSettingsEntry(id = "agent", title = "Second", fragmentClassName = "com.example.ai.Second"),
			)

		val rows = pluginSettingsPreferences(pluginManager)

		assertThat(rows).hasSize(1)
		assertThat(rows.single().titleText).isEqualTo("First")
	}

	@Test
	fun `returns an empty list when pluginManager is null`() {
		assertThat(pluginSettingsPreferences(null)).isEmpty()
	}

	@Test
	fun `returns an empty list when no plugin contributes any entries`() {
		assertThat(pluginSettingsPreferences(fakePluginManager())).isEmpty()
	}

	private fun fakePluginManager(vararg entries: Pair<String, PluginSettingsEntry>): PluginManager {
		val pluginManager = mockk<PluginManager>()
		every { pluginManager.getPluginSettingsEntries() } returns entries.toList()
		return pluginManager
	}
}
