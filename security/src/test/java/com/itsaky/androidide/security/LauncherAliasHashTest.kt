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
import org.junit.Test
import kotlin.random.Random

class LauncherAliasHashTest {
	private val modulo = 5

	/**
	 * FNV-1a 32-bit implemented independently of production (64-bit arithmetic with an explicit
	 * 32-bit mask, then reinterpreted to a signed Int), so a transcription error in the production
	 * constants or step order would diverge from this.
	 */
	private fun referenceIndex(
		signature: String,
		modulo: Int,
	): Int {
		var h = 2166136261L // 0x811c9dc5 offset basis
		for (ch in signature) {
			h = h xor ch.code.toLong()
			h = (h * 16777619L) and 0xFFFFFFFFL // 0x01000193 prime, kept to 32 bits
		}
		return h.toInt().mod(modulo)
	}

	@Test
	fun `index is deterministic for a given signature`() {
		val sig = "samsung_SM-N986U_c2q_abc123"
		assertThat(LauncherAliasManager.stealthVariationIndex(sig, modulo))
			.isEqualTo(LauncherAliasManager.stealthVariationIndex(sig, modulo))
	}

	@Test
	fun `index matches the independent FNV-1a reference`() {
		for (sig in listOf("", "a", "samsung_SM-N986U_c2q", "0", "￿\u0000 zzz")) {
			assertThat(LauncherAliasManager.stealthVariationIndex(sig, modulo))
				.isEqualTo(referenceIndex(sig, modulo))
		}
	}

	@Test
	fun `index is always in range even for signatures that hash to a negative int`() {
		val random = Random(42)
		repeat(5_000) {
			val sig = buildString { repeat(random.nextInt(0, 24)) { append(random.nextInt(0, 0x10000).toChar()) } }
			val index = LauncherAliasManager.stealthVariationIndex(sig, modulo)
			assertThat(index).isAtLeast(0)
			assertThat(index).isLessThan(modulo)
		}
	}

	@Test
	fun `index spreads across more than one bucket over many signatures`() {
		val buckets =
			(0 until 200)
				.map { LauncherAliasManager.stealthVariationIndex("device_signature_$it", modulo) }
				.toSet()
		assertThat(buckets.size).isGreaterThan(1)
	}
}
