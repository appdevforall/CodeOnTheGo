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

	@Test
	fun `an install the tap already confirmed does not ask a second time`() {
		// The whole point of asking at tap time: the user answered "replace it" before the
		// build ran, and the APK it produced still names that same package with that same
		// occupant. Re-asking here would make one Run cost two identical dialogs.
		val answer = QuickBuildClobberConfirmation.Needed("com.example.app")

		assertThat(installTimeClobberConfirmation(atTap = answer, now = answer))
			.isEqualTo(QuickBuildClobberConfirmation.NotNeeded)
	}

	@Test
	fun `an occupant that appeared while the build ran is confirmed even though the tap said nothing`() {
		// The tap-time answer is not a licence for the whole build. Between the tap and the
		// install the user can install the other build type over that package - and then the
		// install really is destructive, about something they were never asked about.
		val decision =
			installTimeClobberConfirmation(
				atTap = QuickBuildClobberConfirmation.NotNeeded,
				now = QuickBuildClobberConfirmation.Needed("com.example.app"),
			)

		assertThat(decision).isEqualTo(QuickBuildClobberConfirmation.Needed("com.example.app"))
	}

	@Test
	fun `an APK naming a different package than the tap asked about is confirmed afresh`() {
		// The variant selection can change while the build runs. The tap asked about the
		// variant it was building, but if what came out names another package, the answer the
		// user gave was about an app this install does not touch.
		val decision =
			installTimeClobberConfirmation(
				atTap = QuickBuildClobberConfirmation.Needed("com.example.app.debug"),
				now = QuickBuildClobberConfirmation.Needed("com.example.app.other"),
			)

		assertThat(decision).isEqualTo(QuickBuildClobberConfirmation.Needed("com.example.app.other"))
	}

	@Test
	fun `an install nobody answered for at tap time asks rather than assuming consent`() {
		// Reachable: an activity recreated mid-build, or a build started by something other
		// than the Run button. Silence is not consent - the confirm is the only thing standing
		// between the user and an overwritten app.
		val decision =
			installTimeClobberConfirmation(
				atTap = null,
				now = QuickBuildClobberConfirmation.Needed("com.example.app"),
			)

		assertThat(decision).isEqualTo(QuickBuildClobberConfirmation.Needed("com.example.app"))
	}

	@Test
	fun `an install with nothing to overwrite stays silent whatever the tap said`() {
		listOf(
			null,
			QuickBuildClobberConfirmation.NotNeeded,
			QuickBuildClobberConfirmation.Needed("com.example.app"),
			QuickBuildClobberConfirmation.NeededForUnknownAppId,
		).forEach { atTap ->
			assertThat(
				installTimeClobberConfirmation(atTap, QuickBuildClobberConfirmation.NotNeeded),
			).isEqualTo(QuickBuildClobberConfirmation.NotNeeded)
		}
	}
}
