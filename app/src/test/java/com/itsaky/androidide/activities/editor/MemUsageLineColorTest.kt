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

package com.itsaky.androidide.activities.editor

import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MutableShiftedLongArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That an unnamed process costs a line colour rather than the editor.
 *
 * This fallback has been established twice and removed twice. It is reached from the once-a-second
 * sample listener and from RecyclerView's bind pass, so throwing here takes the editor down from a
 * timer callback or mid-layout -- for the sake of a colour.
 */
@RunWith(RobolectricTestRunner::class)
class MemUsageLineColorTest {
	private fun process(name: String) =
		MemoryUsageWatcher.ProcessMemoryInfo(
			pid = 1234,
			pname = name,
			_history = MutableShiftedLongArray(4),
		)

	@Test
	fun `the three watched processes keep their colours`() {
		assertThat(BaseEditorActivity.getMemUsageLineColorFor(process("IDE"))).isEqualTo(Color.BLUE)
		assertThat(BaseEditorActivity.getMemUsageLineColorFor(process("Gradle Tooling"))).isEqualTo(Color.RED)
		assertThat(BaseEditorActivity.getMemUsageLineColorFor(process("Gradle Daemon"))).isEqualTo(Color.GREEN)
	}

	@Test
	fun `a process nobody gave a colour gets one anyway`() {
		// Not a throw. The names are only ever supplied by watchProcess call sites today, so this
		// is a guard rather than a live path -- but the cost of being wrong is a crash from a
		// timer callback, and the cost of the guard is one grey line.
		assertThat(BaseEditorActivity.getMemUsageLineColorFor(process("Kotlin Daemon"))).isEqualTo(Color.GRAY)
	}
}
