package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.service.InstallOutcome
import org.junit.Test

/**
 * ADFA-4128 defect #90 tail: an initial-provision failure lands the session in Idle,
 * where returning to CoGo does NOT auto-retry (HostForegrounded is a no-op in Idle) -
 * only a fresh tap does. The surfaced message must not instruct the dead-end action.
 */
class GradleQuickBuildProvisionerMessagesTest {
	@Test
	fun `DIALOG_NOT_SHOWN on initial provision swaps in tap guidance - returning alone is a dead end from Idle`() {
		val outcome =
			InstallOutcome.ConfirmationNotGiven(
				"Your app needs a reinstall - return to CoGo to confirm.",
				InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN,
			)

		val message = GradleQuickBuildProvisioner.initialProvisionMessage(outcome)

		assertThat(message).contains("tap Quick Build")
	}

	@Test
	fun `DECLINED and TIMED_OUT keep the installer's own message - each already names the tap remedy`() {
		listOf(
			InstallOutcome.ConfirmationNotGiven.Reason.DECLINED,
			InstallOutcome.ConfirmationNotGiven.Reason.TIMED_OUT,
		).forEach { reason ->
			val outcome = InstallOutcome.ConfirmationNotGiven("installer message", reason)

			assertThat(GradleQuickBuildProvisioner.initialProvisionMessage(outcome))
				.isEqualTo("installer message")
		}
	}
}
