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
import org.junit.Assert.assertThrows
import org.junit.Test

/** @author Akash Yadav */
class ApiVersionTest {
	@Test
	fun `packs major and minor the way VERSION_CODES_FULL does`() {
		assertThat(ApiVersion.of(36).value).isEqualTo(3600000L)
		assertThat(ApiVersion.of(36, 1).value).isEqualTo(3600001L)
		assertThat(ApiVersion.of(37, 0).value).isEqualTo(3700000L)
	}

	@Test
	fun `unpacks major and minor`() {
		ApiVersion.of(36, 1).apply {
			assertThat(major).isEqualTo(36)
			assertThat(minor).isEqualTo(1)
			assertThat(isKnown).isTrue()
		}
	}

	@Test
	fun `parses a major-only version`() {
		assertThat(ApiVersion.parse("36")).isEqualTo(ApiVersion.of(36))
		assertThat(ApiVersion.parse("1")).isEqualTo(ApiVersion.of(1))
	}

	@Test
	fun `parses a major dot minor version`() {
		assertThat(ApiVersion.parse("36.1")).isEqualTo(ApiVersion.of(36, 1))
		assertThat(ApiVersion.parse("37.0")).isEqualTo(ApiVersion.of(37))
	}

	@Test
	fun `rejects what is not a version`() {
		assertThat(ApiVersion.parse(null)).isNull()
		assertThat(ApiVersion.parse("")).isNull()
		assertThat(ApiVersion.parse("  ")).isNull()
		assertThat(ApiVersion.parse("thirty-six")).isNull()
		assertThat(ApiVersion.parse("36.")).isNull()
		assertThat(ApiVersion.parse(".1")).isNull()
		assertThat(ApiVersion.parse("36.1.2")).isNull()
		assertThat(ApiVersion.parse("-36")).isNull()
		assertThat(ApiVersion.parse("36.-1")).isNull()
		assertThat(ApiVersion.parse("36.100000")).isNull()
	}

	@Test
	fun `orders by major then minor, with UNKNOWN below every real version`() {
		assertThat(ApiVersion.of(36, 1)).isGreaterThan(ApiVersion.of(36))
		assertThat(ApiVersion.of(37)).isGreaterThan(ApiVersion.of(36, 1))
		assertThat(ApiVersion.UNKNOWN).isLessThan(ApiVersion.of(0))
		assertThat(ApiVersion.UNKNOWN.isKnown).isFalse()
	}

	@Test
	fun `renders the minor only when there is one`() {
		assertThat(ApiVersion.of(36).toString()).isEqualTo("36")
		assertThat(ApiVersion.of(37, 0).toString()).isEqualTo("37")
		assertThat(ApiVersion.of(36, 1).toString()).isEqualTo("36.1")
		assertThat(ApiVersion.UNKNOWN.toString()).isEqualTo("unknown")
	}

	@Test
	fun `reports an unknown version as -1 rather than API 0`() {
		assertThat(ApiVersion.UNKNOWN.major).isEqualTo(-1)
		assertThat(ApiVersion.UNKNOWN.minor).isEqualTo(-1)
	}

	@Test
	fun `rejects components that would alias onto another version`() {
		assertThrows(IllegalArgumentException::class.java) { ApiVersion.of(36, 100_000) }
		assertThrows(IllegalArgumentException::class.java) { ApiVersion.of(36, -1) }
		assertThrows(IllegalArgumentException::class.java) { ApiVersion.of(-1) }
	}
}
