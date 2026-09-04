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
	fun `every deep-link pathPrefix in the manifest matches the prefix buildUrl emits`() {
		// Located by trying both roots rather than assuming one: the Gradle test task runs with the
		// module directory as the working directory, an IDE run configuration often uses the repo
		// root, and a wrong guess would fail as "manifest missing" rather than as real drift.
		val manifest =
			listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
				.map(::File)
				.firstOrNull { it.isFile }
		assertThat(manifest).isNotNull()

		// Scoped to <data> elements that name a deep-link host, NOT every pathPrefix in the file. An
		// unrelated App Link added elsewhere in the manifest is not drift in this scheme, and failing
		// on it would point the reader at DeepLinkRequest for someone else's change.
		val elements =
			Regex("""<data\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
				.findAll(manifest!!.readText())
				.map { it.value }
				.filter { it.contains("appdevforall.org") }
				.toList()
		val declared = elements.mapNotNull { attribute(it, "android:pathPrefix") }

		// If the intent-filter stops declaring a prefix, this test must fail rather than vacuously
		// pass over an empty list -- there are two <data> elements today, one per verified host.
		assertThat(declared).isNotEmpty()

		// Taken from a URL the builder actually produces, so this asserts against the emitted shape
		// rather than against a second copy of the constant.
		val built = DeepLinkRequest.buildUrl("MyApp", "Main.kt", line = 1, column = 1)
		val emitted = Uri.parse(built!!)

		for (prefix in declared) {
			assertThat(emitted.path).startsWith(prefix)
		}

		// Host and scheme too, not only the path. An intent-filter matches on all three, so changing
		// CANONICAL_HOST or SCHEME without the manifest breaks delivery exactly as changing the prefix
		// does -- and a path-only assertion stays green through both.
		val hosts = elements.mapNotNull { attribute(it, "android:host") }.toSet()
		val schemes = elements.mapNotNull { attribute(it, "android:scheme") }.toSet()
		assertThat(hosts).isNotEmpty()
		assertThat(schemes).isNotEmpty()
		assertThat(hosts).contains(emitted.host)
		assertThat(schemes).contains(emitted.scheme)
	}

	private fun attribute(
		element: String,
		name: String,
	): String? = Regex("""$name\s*=\s*"([^"]*)"""").find(element)?.groupValues?.get(1)
}
