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

class FaceDownDetectorTest {
	private fun firesFor(vararg zs: Float): List<Boolean> {
		val detector = FaceDownDetector()
		return zs.map(detector::onGravityZ)
	}

	@Test
	fun `a phone already face down, held overhead lying on your back, never fires`() {
		assertThat(firesFor(-9.8f, -9.7f, -9.8f, -8.5f)).doesNotContain(true)
	}

	@Test
	fun `tilting about the threshold while lying on your back never fires`() {
		assertThat(firesFor(-9.8f, -5.0f, -9.0f, -4.0f, -9.5f)).doesNotContain(true)
	}

	@Test
	fun `a flip from upright to face down fires once`() {
		assertThat(firesFor(6.0f, 2.0f, -7.0f, -9.8f)).containsExactly(false, false, true, false).inOrder()
	}

	@Test
	fun `wobbling about the threshold after firing does not fire again until the phone is up again`() {
		assertThat(firesFor(5.0f, -9.0f, -5.0f, -9.0f, 4.0f, -9.0f))
			.containsExactly(false, true, false, false, false, true)
			.inOrder()
	}

	@Test
	fun `a reset forgets that the phone was upright`() {
		val detector = FaceDownDetector()
		detector.onGravityZ(5.0f)
		detector.reset()
		assertThat(detector.onGravityZ(-9.8f)).isFalse()
	}
}
