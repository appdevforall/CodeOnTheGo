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

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WipeManagerTest {
	@get:Rule
	val tmp = TemporaryFolder()

	/**
	 * Records the teardown calls and the order relative to deletion. [sourceStillPresentAtStop]
	 * captures whether a known target still existed when [stopEverything] ran, which proves teardown
	 * happens before any deletion. [markerPresentAtClear] captures whether [markerWitness] already
	 * existed when [clearPreferences] ran, which proves the durable marker is written before prefs are
	 * blanked (ADFA-6510 review F02).
	 */
	private class FakeTeardown(
		private val witness: File,
		private val markerWitness: File? = null,
	) : WipeTeardown {
		val calls = mutableListOf<String>()
		var sourceStillPresentAtStop: Boolean? = null
		var markerPresentAtClear: Boolean? = null

		override fun stopEverything() {
			sourceStillPresentAtStop = witness.exists()
			calls += "stop"
		}

		override fun resetBookkeeping() {
			calls += "reset"
		}

		override fun clearPreferences() {
			markerPresentAtClear = markerWitness?.exists()
			calls += "clear"
		}
	}

	private fun plan(
		secure: List<File>,
		fast: List<File>,
		markers: List<File>,
	) = WipePlan(secureTargets = secure, fastDeleteTargets = fast, markerFiles = markers)

	@Test
	fun `real wipe shreds secure targets, deletes bulk, writes markers, and tears down`() {
		val source = tmp.newFolder("CodeOnTheGoProjects").also { File(it, "App.kt").writeText("secret") }
		val db = tmp.newFile("RecentProject_database").apply { writeText("rows") }
		val caches = tmp.newFolder("caches", "gradle").also { File(it, "big.bin").writeBytes(ByteArray(8192)) }
		val marker = File(tmp.root, "markers/.pip")
		val teardown = FakeTeardown(witness = source)

		val result =
			WipeManager.wipe(
				plan(secure = listOf(source, db), fast = listOf(caches.parentFile!!), markers = listOf(marker)),
				teardown,
				dryRun = false,
			)

		assertThat(source.exists()).isFalse()
		assertThat(db.exists()).isFalse()
		assertThat(caches.parentFile!!.exists()).isFalse()
		assertThat(marker.exists()).isTrue()
		assertThat(result.dryRun).isFalse()
		assertThat(result.secureShredded).isEqualTo(2) // App.kt + the db file
		assertThat(result.fastDeleted).isEqualTo(1)
		assertThat(result.markersWritten).isEqualTo(1)
		assertThat(teardown.calls).containsExactly("stop", "reset", "clear").inOrder()
		// Teardown must run before anything is deleted.
		assertThat(teardown.sourceStillPresentAtStop).isTrue()
	}

	@Test
	fun `the durable marker is written before preferences are cleared (crash-window safety)`() {
		val source = tmp.newFolder("CodeOnTheGoProjects").also { File(it, "App.kt").writeText("secret") }
		val marker = File(tmp.root, "markers/.pip")
		val teardown = FakeTeardown(witness = source, markerWitness = marker)

		WipeManager.wipe(
			plan(secure = listOf(source), fast = emptyList(), markers = listOf(marker)),
			teardown,
			dryRun = false,
		)

		// If the process dies between clearPreferences() and the marker write, the install would look
		// un-wiped on next launch. The marker must already exist by the time prefs are blanked.
		assertThat(teardown.markerPresentAtClear).isTrue()
	}

	@Test
	fun `dry run destroys nothing, writes no markers, and skips state mutation`() {
		val source = tmp.newFolder("CodeOnTheGoProjects").also { File(it, "App.kt").writeText("secret") }
		val caches = tmp.newFolder("caches")
		val marker = File(tmp.root, "markers/.pip")
		val teardown = FakeTeardown(witness = source)

		val result =
			WipeManager.wipe(
				plan(secure = listOf(source), fast = listOf(caches), markers = listOf(marker)),
				teardown,
				dryRun = true,
			)

		// Nothing destroyed.
		assertThat(source.exists()).isTrue()
		assertThat(File(source, "App.kt").exists()).isTrue()
		assertThat(caches.exists()).isTrue()
		assertThat(marker.exists()).isFalse()
		// Teardown still runs (QA verifies it), but no state is mutated.
		assertThat(result.dryRun).isTrue()
		assertThat(teardown.calls).containsExactly("stop")
		assertThat(result.secureShredded).isEqualTo(0)
		assertThat(result.fastDeleted).isEqualTo(0)
		assertThat(result.markersWritten).isEqualTo(0)
	}

	@Test
	fun `enumerated targets are reported in both modes`() {
		val a = tmp.newFolder("a")
		val b = tmp.newFolder("b")
		val teardown = FakeTeardown(witness = a)

		val result = WipeManager.wipe(plan(listOf(a), listOf(b), emptyList()), teardown, dryRun = true)

		assertThat(result.enumeratedTargets).containsExactly(a.absolutePath, b.absolutePath).inOrder()
	}
}
