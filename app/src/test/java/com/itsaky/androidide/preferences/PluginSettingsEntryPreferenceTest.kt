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

import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The row is re-created from its parcel after process death and rebuilds its own launch intent from
 * these fields, so losing any of them would leave a dead preference row behind.
 */
@RunWith(RobolectricTestRunner::class)
class PluginSettingsEntryPreferenceTest {
	@Test
	fun parcelRoundTripKeepsEveryField() {
		val original =
			PluginSettingsEntryPreference(
				pluginId = "com.example.ai",
				entryId = "agent",
				titleText = "Agent",
				summaryText = "Configure the assistant",
				fragmentClassName = "com.example.ai.SettingsFragment",
			)

		assertThat(roundTrip(original)).isEqualTo(original)
	}

	@Test
	fun parcelRoundTripKeepsANullSummary() {
		val original =
			PluginSettingsEntryPreference(
				pluginId = "com.example.ai",
				entryId = "agent",
				titleText = "Agent",
				summaryText = null,
				fragmentClassName = "com.example.ai.SettingsFragment",
			)

		val restored = roundTrip(original)

		assertThat(restored).isEqualTo(original)
		assertThat(restored.summaryText).isNull()
	}

	@Test
	fun keyNamespacesThePluginAndTheEntry() {
		val preference =
			PluginSettingsEntryPreference(
				pluginId = "com.example.ai",
				entryId = "agent",
				titleText = "Agent",
				summaryText = null,
				fragmentClassName = "com.example.ai.SettingsFragment",
			)

		assertThat(preference.key).isEqualTo("idepref_plugin_settings_14:com.example.ai:agent")
	}

	@Test
	fun keyDoesNotCollideWhenTheDotSplitIsAmbiguous() {
		// Without a length-prefixed split point, both pairs below would join to the
		// identical string "com.example.ai.agent" and collide.
		val first =
			PluginSettingsEntryPreference(
				pluginId = "com.example",
				entryId = "ai.agent",
				titleText = "First",
				summaryText = null,
				fragmentClassName = "com.example.Frag",
			)
		val second =
			PluginSettingsEntryPreference(
				pluginId = "com.example.ai",
				entryId = "agent",
				titleText = "Second",
				summaryText = null,
				fragmentClassName = "com.example.ai.Frag",
			)

		assertThat(first.key).isNotEqualTo(second.key)
	}

	@Suppress("DEPRECATION")
	private fun roundTrip(preference: PluginSettingsEntryPreference): PluginSettingsEntryPreference {
		val parcel = Parcel.obtain()
		return try {
			parcel.writeParcelable(preference, 0)
			parcel.setDataPosition(0)
			checkNotNull(
				parcel.readParcelable<PluginSettingsEntryPreference>(
					PluginSettingsEntryPreference::class.java.classLoader,
				),
			)
		} finally {
			parcel.recycle()
		}
	}
}
