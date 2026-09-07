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

package com.itsaky.androidide.api

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Covers the main-thread guard on [BuildOutputProvider]'s session-file fallback. Robolectric is
 * what makes this testable: the sibling [BuildOutputProviderTest] runs with no [android.os.Looper]
 * at all, which is indistinguishable from "not the main thread".
 */
@RunWith(RobolectricTestRunner::class)
class BuildOutputProviderMainThreadTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@After
	fun tearDown() {
		BuildOutputProvider.setSessionDirectoryForTest(null)
		BuildOutputProvider.clearBottomSheet()
	}

	@Test
	fun `givenASessionFile_whenReadOnTheMainThread_thenNothingIsReadFromDisk`() {
		writeSessionFile("> Task :app:compileV8DebugKotlin\n")

		// Robolectric runs the test body on the main thread, so this is the ANR case.
		assertThat(BuildOutputProvider.getBuildOutputContent()).isNull()
	}

	@Test
	fun `givenASessionFile_whenReadOffTheMainThread_thenItIsReturned`() {
		writeSessionFile("e: Foo.kt:12:5 unresolved reference: bar\n")

		assertThat(offMainThread { BuildOutputProvider.getBuildOutputContent() })
			.isEqualTo("e: Foo.kt:12:5 unresolved reference: bar\n")
	}

	/**
	 * Runs [block] on a background thread while pumping the main looper. The live read dispatches
	 * to the main thread, and under Robolectric that queue only drains when this thread drains it --
	 * blocking on the result instead would deadlock the two against each other.
	 */
	private fun <T> offMainThread(block: () -> T): T {
		val executor = Executors.newSingleThreadExecutor()
		try {
			val result = executor.submit(Callable { block() })
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
			while (!result.isDone && System.nanoTime() < deadline) {
				shadowOf(Looper.getMainLooper()).idle()
				Thread.sleep(5)
			}
			return result.get(1, TimeUnit.SECONDS)
		} finally {
			executor.shutdownNow()
		}
	}

	private fun writeSessionFile(content: String) {
		File(tempFolder.root, BuildOutputViewModel.SESSION_FILE_NAME).writeText(content)
		BuildOutputProvider.setSessionDirectoryForTest(tempFolder.root)
	}
}
