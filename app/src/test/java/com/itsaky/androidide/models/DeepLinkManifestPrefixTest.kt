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

package com.itsaky.androidide.models

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.FileProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The deep-link path prefix is spelled in two places that cannot see each other: `DeepLinkRequest`,
 * which builds and parses URLs, and `AndroidManifest.xml`, whose `android:pathPrefix` decides
 * whether Android delivers the link to this app at all.
 *
 * Nothing else catches a disagreement. Change the Kotlin side alone and `buildUrl` and `parse` still
 * agree with each other, every other test stays green, and every generated link quietly opens in a
 * browser instead of the editor.
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkManifestPrefixTest {
	@Test
	fun `the manifest's deep-link filter matches the scheme, host and path buildUrl emits`() {
		// Located from the repo's own root sentinel rather than by guessing relative paths: the
		// previous two-guess list failed as "manifest missing" under any working directory that was
		// neither the module nor the repo root, which is the misdiagnosis it was meant to avoid.
		val manifest = FileProvider.projectRoot().resolve("app/src/main/AndroidManifest.xml").toFile()
		assertThat(manifest.isFile).isTrue()

		// Taken from a URL the builder actually produces, so every assertion below is against the
		// emitted shape rather than a second copy of a constant.
		val emitted = Uri.parse(DeepLinkRequest.buildUrl("MyApp", "Main.kt", line = 1, column = 1)!!)

		// The elements are selected by the host the builder emits, not by a hard-coded domain. A
		// literal here would be a third copy of CANONICAL_HOST -- and renaming the domain correctly
		// in both Kotlin and the manifest would then fail this test for a change with no drift at all.
		val elements =
			Regex("""<data\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
				.findAll(manifest.readText())
				.map { it.value }
				.filter { attribute(it, "android:host") == emitted.host }
				.toList()

		// Not a vacuous pass: if no <data> element names the emitted host, delivery is broken and that
		// is the drift this test exists to catch.
		assertThat(elements).isNotEmpty()

		// All three, because an intent-filter matches on all three. A path-only assertion stayed green
		// through a changed CANONICAL_HOST or SCHEME, which breaks delivery just as surely.
		for (element in elements) {
			assertThat(emitted.path).startsWith(attribute(element, "android:pathPrefix"))
			assertThat(attribute(element, "android:scheme")).isEqualTo(emitted.scheme)
		}
	}

	private fun attribute(
		element: String,
		name: String,
	): String? = Regex("""$name\s*=\s*"([^"]*)"""").find(element)?.groupValues?.get(1)
}
