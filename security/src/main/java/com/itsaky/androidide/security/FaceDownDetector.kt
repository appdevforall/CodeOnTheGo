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

/**
 * Decides when the face-down trigger fires, from the gravity Z reading (about -9.8 with the screen
 * fully facing down). It arms only on a face-up or upright reading, so a phone that is already screen
 * down when the IDE opens - held overhead while lying on your back - never fires; it disarms on
 * firing, so wobbling about the threshold fires once.
 */
internal class FaceDownDetector {
	companion object {
		const val FACE_DOWN_Z = -6.0f

		/** At or above this the screen faces up or stands upright, so the next flip down may fire. */
		const val ARMED_Z = 0.0f
	}

	private var armed = false

	/** Forgets the last orientation, so a flip must again start from face up or upright. */
	fun reset() {
		armed = false
	}

	/** @return true when this reading completes a flip from face up or upright to face down. */
	fun onGravityZ(z: Float): Boolean {
		if (z >= ARMED_Z) {
			armed = true
			return false
		}
		if (armed && z <= FACE_DOWN_Z) {
			armed = false
			return true
		}
		return false
	}
}
