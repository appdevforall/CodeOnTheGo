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

package com.itsaky.androidide.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NeutralizationMarkerTest {
	private val context: Context = RuntimeEnvironment.getApplication()

	@Before
	fun clean() {
		NeutralizationMarker.pipFile(context).delete()
		NeutralizationMarker.pip2File(context).delete()
	}

	@Test
	fun `a fresh install is not wiped`() {
		assertThat(NeutralizationMarker.isWiped(context)).isFalse()
	}

	@Test
	fun `mark makes the install read as wiped`() {
		NeutralizationMarker.mark(context)
		assertThat(NeutralizationMarker.isWiped(context)).isTrue()
	}

	@Test
	fun `either marker alone is enough`() {
		NeutralizationMarker.pip2File(context).apply { parentFile?.mkdirs() }.createNewFile()
		assertThat(NeutralizationMarker.isWiped(context)).isTrue()
	}

	private val stealth = "Code Breaker"

	@Test
	fun `tamper signature - blank prefs + stealth alias + no marker`() {
		assertThat(
			NeutralizationMarker.isTamperSignature(
				wiped = false,
				hideArmed = false,
				wipeArmed = false,
				currentLauncherLabel = stealth,
				stealthLabel = stealth,
			),
		).isTrue()
	}

	@Test
	fun `not tamper - a fresh install still shows the default launcher label`() {
		assertThat(
			NeutralizationMarker.isTamperSignature(
				wiped = false,
				hideArmed = false,
				wipeArmed = false,
				currentLauncherLabel = "Code On The Go",
				stealthLabel = stealth,
			),
		).isFalse()
	}

	@Test
	fun `not tamper - a marker is already present (handled as wiped)`() {
		assertThat(
			NeutralizationMarker.isTamperSignature(
				wiped = true,
				hideArmed = false,
				wipeArmed = false,
				currentLauncherLabel = stealth,
				stealthLabel = stealth,
			),
		).isFalse()
	}

	@Test
	fun `not tamper - still armed, so prefs are not blank`() {
		assertThat(
			NeutralizationMarker.isTamperSignature(
				wiped = false,
				hideArmed = true,
				wipeArmed = false,
				currentLauncherLabel = stealth,
				stealthLabel = stealth,
			),
		).isFalse()
		assertThat(
			NeutralizationMarker.isTamperSignature(
				wiped = false,
				hideArmed = false,
				wipeArmed = true,
				currentLauncherLabel = stealth,
				stealthLabel = stealth,
			),
		).isFalse()
	}
}
