package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The property these tests exist for: the notice's Restart acts on the standard build that is
 * running when it is TAPPED, not on the one that was running when it was shown.
 *
 * The gap they pin (ADFA-4128): the gate was read once, at `show()`. A notice raised during a
 * standard build kept a dead Restart for the whole life of that dialog - the build finishing never
 * re-enabled it, and nothing on screen said why it did nothing - while a notice raised before a
 * build kept a live Restart that tore the session down into a reprovision the build then refused.
 */
class QuickBuildWontStayUpRestartTest {
	private var standardBuildRunning = false
	private var explained = 0
	private var restarted = 0
	private var dismissed = 0

	private val restart =
		QuickBuildWontStayUpRestart(
			isBlockedByStandardBuild = { standardBuildRunning },
			explainBlocked = { explained++ },
			restartAndReprovision = { restarted++ },
			dismiss = { dismissed++ },
		)

	@Test
	fun `a tap with no standard build running restarts, and closes the notice`() {
		restart.onTapped()

		assertThat(restarted).isEqualTo(1)
		assertThat(dismissed).isEqualTo(1)
		assertThat(explained).isEqualTo(0)
	}

	@Test
	fun `a tap while a standard build runs says so instead, and leaves the notice up`() {
		standardBuildRunning = true

		restart.onTapped()

		// Leaving the notice up is what makes the reason actionable: the only other route back
		// to it is another Quick Build failure streak.
		assertThat(explained).isEqualTo(1)
		assertThat(restarted).isEqualTo(0)
		assertThat(dismissed).isEqualTo(0)
	}

	@Test
	fun `the same notice restarts once the standard build that blocked it finishes`() {
		standardBuildRunning = true
		restart.onTapped()

		standardBuildRunning = false
		restart.onTapped()

		assertThat(restarted).isEqualTo(1)
		assertThat(dismissed).isEqualTo(1)
		assertThat(explained).isEqualTo(1)
	}

	@Test
	fun `a notice raised before a standard build does not restart into it`() {
		// The mirror case, measured on a device 5 times out of 5: shown while nothing was
		// building, the Restart stayed live for the whole build that started afterwards.
		restart.onTapped()
		standardBuildRunning = true

		restart.onTapped()

		assertThat(restarted).isEqualTo(1)
		assertThat(explained).isEqualTo(1)
	}
}
