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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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
	fun `every manifest pathPrefix matches the prefix buildUrl emits`() {
		val manifest = File("src/main/AndroidManifest.xml")
		assertThat(manifest.exists()).isTrue()

		val declared =
			Regex("""android:pathPrefix\s*=\s*"([^"]*)"""")
				.findAll(manifest.readText())
				.map { it.groupValues[1] }
				.toList()

		// If the intent-filter stops declaring a prefix, this test must fail rather than vacuously
		// pass over an empty list -- there are two <data> elements today, one per verified host.
		assertThat(declared).isNotEmpty()

		// Taken from a URL the builder actually produces, so this asserts against the emitted shape
		// rather than against a second copy of the constant.
		val built = DeepLinkRequest.buildUrl("MyApp", "Main.kt", line = 1, column = 1)
		val path = Uri.parse(built!!).path!!

		for (prefix in declared) {
			assertThat(path).startsWith(prefix)
		}
	}
}
