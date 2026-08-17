package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every [QuickBuildMessage] resolves to the string the user should read.
 *
 * The compiler already forces the `when` to be exhaustive, so a missing case cannot ship.
 * What it cannot check is whether each case maps to the RIGHT resource, or whether a case
 * carrying values actually substitutes them - swap two arms and everything still builds.
 * That is what these pin.
 *
 * Robolectric for a real resource-resolving [Context]; the values are read from
 * `values/strings.xml` rather than hardcoded, so translating a string does not break the
 * test while re-pointing an arm does.
 */
@RunWith(RobolectricTestRunner::class)
class QuickBuildMessagesTest {
	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private fun assertResolvesTo(
		message: QuickBuildMessage,
		expectedId: Int,
		vararg formatArgs: Any,
	) {
		assertThat(message.resolve(context)).isEqualTo(context.getString(expectedId, *formatArgs))
	}

	@Test
	fun `a literal passes its text through untouched`() {
		// The deliberate exception: text already final because nothing can translate it.
		assertThat(QuickBuildMessage.Literal("PackageManager said no").resolve(context))
			.isEqualTo("PackageManager said no")
	}

	@Test
	fun `each valueless case resolves to its own string`() {
		assertResolvesTo(QuickBuildMessage.ReinstallReturnToCoGo, R.string.quick_build_reinstall_return_to_cogo)
		assertResolvesTo(QuickBuildMessage.ReinstallDeclined, R.string.quick_build_reinstall_declined)
		assertResolvesTo(QuickBuildMessage.ReinstallWaitingForGradle, R.string.quick_build_reinstall_waiting_for_gradle)
		assertResolvesTo(QuickBuildMessage.InstallCouldNotStart, R.string.quick_build_install_could_not_start)
		assertResolvesTo(QuickBuildMessage.InstallFailed, R.string.quick_build_install_failed)
		assertResolvesTo(QuickBuildMessage.RebuildFailed, R.string.quick_build_rebuild_failed)
		assertResolvesTo(QuickBuildMessage.DaemonRejectedConfiguration, R.string.quick_build_daemon_rejected_config)
	}

	/**
	 * The value-carrying cases, each asserted with a value that would be visibly absent if
	 * the arm dropped it or passed the wrong one.
	 */
	@Test
	fun `each case carrying a value substitutes it`() {
		assertResolvesTo(QuickBuildMessage.ReinstallTimedOut(seconds = 180), R.string.quick_build_reinstall_timed_out, 180L)
		assertResolvesTo(
			QuickBuildMessage.InstalledButUnresolvable(packageName = "com.example.app"),
			R.string.quick_build_installed_but_unresolvable,
			"com.example.app",
		)
		assertResolvesTo(
			QuickBuildMessage.ForeignAppInstalled(applicationId = "com.example.other"),
			R.string.quick_build_foreign_app_installed,
			"com.example.other",
		)
		assertResolvesTo(
			QuickBuildMessage.DaemonRestartFailed(detail = "spawn refused"),
			R.string.quick_build_daemon_restart_failed,
			"spawn refused",
		)
		assertResolvesTo(
			QuickBuildMessage.ScratchDirUnavailable(path = "/data/scratch"),
			R.string.quick_build_scratch_dir_unavailable,
			"/data/scratch",
		)
	}

	/**
	 * Two numbers in one string, so a swapped pair is the plausible bug: 512 needed with
	 * 64 free must never read as 64 needed with 512 free.
	 */
	@Test
	fun `not-enough-storage keeps required and available the right way round`() {
		val resolved = QuickBuildMessage.NotEnoughStorage(requiredMb = 512, availableMb = 64).resolve(context)

		assertThat(resolved).isEqualTo(context.getString(R.string.quick_build_not_enough_storage, 512L, 64L))
		assertThat(resolved).isNotEqualTo(context.getString(R.string.quick_build_not_enough_storage, 64L, 512L))
	}

	/**
	 * No arm may resolve to blank: an empty string reaches `flashError` as an error banner
	 * with nothing in it, which reads as a UI bug rather than a build failure.
	 */
	@Test
	fun `no case resolves to blank text`() {
		val everyCase =
			listOf(
				QuickBuildMessage.Literal("x"),
				QuickBuildMessage.ReinstallReturnToCoGo,
				QuickBuildMessage.ReinstallDeclined,
				QuickBuildMessage.ReinstallTimedOut(180),
				QuickBuildMessage.ReinstallWaitingForGradle,
				QuickBuildMessage.InstallCouldNotStart,
				QuickBuildMessage.InstallFailed,
				QuickBuildMessage.InstalledButUnresolvable("com.example.app"),
				QuickBuildMessage.ForeignAppInstalled("com.example.other"),
				QuickBuildMessage.RebuildFailed,
				QuickBuildMessage.DaemonRestartFailed("detail"),
				QuickBuildMessage.NotEnoughStorage(512, 64),
				QuickBuildMessage.ScratchDirUnavailable("/data/scratch"),
				QuickBuildMessage.DaemonRejectedConfiguration,
			)

		everyCase.forEach { assertThat(it.resolve(context)).isNotEmpty() }
		// Distinct copy per case, so no two arms point at the same resource by mistake.
		assertThat(everyCase.map { it.resolve(context) }.toSet()).hasSize(everyCase.size)
	}
}
