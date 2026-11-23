package com.itsaky.androidide.actions.build

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The "Replace the installed app?" question on a Quick Build tap. A project that turns the SQLite
 * debugger on is refused before anything is installed, so the question must not be asked: on a
 * Galaxy Note 20 it came before the refusal, and its Cancel hid the refusal that the status line's
 * "tap for details" exists to show.
 */
class QuickBuildReplaceQuestionTest {
	@Test
	fun `the question is not asked when the SQLite debugger will refuse the build`() =
		runTest {
			var asked = false

			val proceed =
				QuickBuildAction.replaceConfirmedUnlessRefused(refused = true) {
					asked = true
					false
				}

			assertThat(asked).isFalse()
			assertThat(proceed).isTrue()
		}

	@Test
	fun `otherwise the question is asked, and its answer decides`() =
		runTest {
			var asked = 0

			val replace = QuickBuildAction.replaceConfirmedUnlessRefused(refused = false) { ++asked > 0 }
			val cancel = QuickBuildAction.replaceConfirmedUnlessRefused(refused = false) { ++asked < 0 }

			assertThat(asked).isEqualTo(2)
			assertThat(replace).isTrue()
			assertThat(cancel).isFalse()
		}
}
