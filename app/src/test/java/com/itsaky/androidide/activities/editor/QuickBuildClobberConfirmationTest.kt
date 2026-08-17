package com.itsaky.androidide.activities.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The confirm-on-switch gate (ADFA-4128) has to fail CLOSED. Quick Build and Standard Run
 * install under the same real applicationId, so whichever runs second overwrites the app the
 * other installed - and the review finding here was that an applicationId which did not
 * resolve took the same branch as "nothing to overwrite", installing silently over an app the
 * user had put there by hand.
 */
class QuickBuildClobberConfirmationTest {
	@Test
	fun `an unresolvable application id confirms rather than replacing the installed app silently`() {
		val decision =
			quickBuildClobberConfirmation(realApplicationId = null) {
				error("the check cannot run without an application id")
			}

		assertThat(decision).isEqualTo(QuickBuildClobberConfirmation.NeededForUnknownAppId)
	}

	@Test
	fun `an occupied slot confirms and carries the id the dialog names`() {
		val decision = quickBuildClobberConfirmation("com.example.app") { true }

		assertThat(decision).isEqualTo(QuickBuildClobberConfirmation.Needed("com.example.app"))
	}

	@Test
	fun `the fast path stays fast - a slot with nothing to overwrite is not confirmed`() {
		var asked: String? = null

		val decision =
			quickBuildClobberConfirmation("com.example.app") {
				asked = it
				false
			}

		assertThat(decision).isEqualTo(QuickBuildClobberConfirmation.NotNeeded)
		// The id the check is asked about is the project's own, not the proxy app's.
		assertThat(asked).isEqualTo("com.example.app")
	}
}
