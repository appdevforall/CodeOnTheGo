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

package com.itsaky.androidide.xml.versions

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Reads checked-in platform fixtures rather than an installed SDK, so the major.minor grammar is
 * covered on a machine with any set of platforms installed -- or none.
 *
 * @author Akash Yadav
 */
@RunWith(RobolectricTestRunner::class)
class ApiVersionsMinorSdkTest {
	private val registry = ApiVersionsRegistry.getInstance()

	@Before
	fun setup() {
		registry.clear()
	}

	@Test
	fun `reads major dot minor versions`() {
		val versions = registry.forPlatformDir(fixture("platform-minor-sdk"))
		assertThat(versions).isNotNull()

		versions!!.getClass("a.B").apply {
			assertThat(this).isNotNull()
			assertThat(this!!.since).isEqualTo(ApiVersion.of(36))

			assertThat(getField("NEW")!!.since).isEqualTo(ApiVersion.of(36, 1))
			assertThat(getField("NEXT")!!.since).isEqualTo(ApiVersion.of(37))
			assertThat(getField("OLD")!!.deprecated).isEqualTo(ApiVersion.of(36, 1))
			assertThat(getMethod("gone")!!.removed).isEqualTo(ApiVersion.of(37))
		}
	}

	@Test
	fun `treats an unreadable version as unknown rather than failing the platform`() {
		val versions = registry.forPlatformDir(fixture("platform-minor-sdk"))
		assertThat(versions).isNotNull()

		versions!!.getClass("a.B")!!.getField("JUNK").apply {
			assertThat(this).isNotNull()
			assertThat(this!!.since).isEqualTo(ApiVersion.UNKNOWN)
		}
	}

	@Test
	fun `returns null instead of propagating when the file is not an api table`() {
		assertThat(registry.forPlatformDir(fixture("platform-malformed"))).isNull()
	}

	private fun fixture(name: String): File {
		val resource =
			checkNotNull(javaClass.classLoader!!.getResource("$name/data/api-versions.xml")) {
				"Missing test fixture: $name"
			}
		return File(resource.toURI()).parentFile.parentFile
	}
}
