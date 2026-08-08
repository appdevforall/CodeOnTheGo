package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMessage
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
				QuickBuildMessage.ReinstallReturnToCoGo,
				InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN,
			)

		val override = GradleQuickBuildProvisioner.initialProvisionMessageOverride(outcome)

		assertThat(override).isEqualTo(R.string.quick_build_reinstall_tap_again)
	}

	@Test
	fun `DECLINED and TIMED_OUT keep the installer's own message - each already names the tap remedy`() {
		listOf(
			InstallOutcome.ConfirmationNotGiven.Reason.DECLINED,
			InstallOutcome.ConfirmationNotGiven.Reason.TIMED_OUT,
		).forEach { reason ->
			val outcome = InstallOutcome.ConfirmationNotGiven(QuickBuildMessage.Literal("installer message"), reason)

			assertThat(GradleQuickBuildProvisioner.initialProvisionMessageOverride(outcome)).isNull()
		}
	}
}
