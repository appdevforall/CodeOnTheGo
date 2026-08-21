package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.viewmodel.EditorViewModel
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.junit.Test

/**
 * Which Quick Build outcomes raise a flashbar over the editor.
 *
 * The behaviour this exists to pin is the recovery path, because the obvious implementation
 * silently never fires: a fixed build arrives as `Failed -> Building -> UpToDate`, so the status
 * immediately before the good build is [QuickBuildStatus.Building], not the failure. Every
 * recovery test below therefore walks the real three-step sequence rather than jumping straight
 * from a failure to a landed build.
 *
 * The other half is restraint - a Quick Build lands on every save, so the tests assert as hard on
 * what must NOT flash as on what must.
 */
class QuickBuildFlashesTest {
	private fun compileError(message: String = "boom") =
		SessionFailure.CompileError(
			listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, message, "/p/Foo.kt", 12, 5)),
		)

	private fun failed(failure: SessionFailure = compileError()) = QuickBuildStatus.Failed(4L, failure)

	private fun landed(
		generation: Long = 5L,
		durationMillis: Long? = 900L,
	) = QuickBuildStatus.UpToDate(generation, durationMillis)

	@Test
	fun `a compile failure flashes the error`() {
		val flashes = QuickBuildFlashes()

		val flash = flashes.next(QuickBuildStatus.Building(4L), failed())

		assertThat(flash).isEqualTo(QuickBuildFlash.Failure(R.string.quick_build_flash_failed))
	}

	@Test
	fun `saving a file that is still broken does not flash again`() {
		val flashes = QuickBuildFlashes()
		val failure = compileError()
		flashes.next(QuickBuildStatus.Building(4L), failed(failure))

		// The real sequence, and the one a previous-vs-current comparison gets wrong: the user
		// saves again without fixing it, so a build runs in between and the status immediately
		// before the repeat failure is Building, not the failure it repeats.
		assertThat(flashes.next(failed(failure), QuickBuildStatus.Building(4L))).isNull()
		val flash = flashes.next(QuickBuildStatus.Building(4L), failed(failure))

		assertThat(flash).isNull()
	}

	@Test
	fun `the same failure settling does not flash again`() {
		val flashes = QuickBuildFlashes()
		val failure = compileError()
		flashes.next(QuickBuildStatus.Building(4L), failed(failure))

		// Same failure re-emitted as the derived status settles through another state.
		val flash = flashes.next(QuickBuildStatus.Reconnecting(4L), failed(failure))

		assertThat(flash).isNull()
	}

	@Test
	fun `re-breaking a file the same way after a fix flashes again`() {
		val flashes = QuickBuildFlashes()
		val failure = compileError()
		flashes.next(QuickBuildStatus.Building(4L), failed(failure))
		flashes.next(failed(failure), QuickBuildStatus.Building(4L))
		flashes.next(QuickBuildStatus.Building(4L), landed())

		// Cleared, so the identical error is news again - suppressing it would leave a later
		// save silently broken.
		val flash = flashes.next(QuickBuildStatus.Building(5L), failed(failure))

		assertThat(flash).isEqualTo(QuickBuildFlash.Failure(R.string.quick_build_flash_failed))
	}

	@Test
	fun `a different failure flashes again`() {
		val flashes = QuickBuildFlashes()
		flashes.next(QuickBuildStatus.Building(4L), failed(compileError("first")))

		val flash = flashes.next(failed(compileError("first")), failed(compileError("second")))

		assertThat(flash).isEqualTo(QuickBuildFlash.Failure(R.string.quick_build_flash_failed))
	}

	@Test
	fun `the build that fixes a failure flashes success`() {
		val flashes = QuickBuildFlashes()
		val broken = failed()
		flashes.next(QuickBuildStatus.Building(4L), broken)

		// The real sequence: the user fixes the file and saves, so a build runs before it lands.
		assertThat(flashes.next(broken, QuickBuildStatus.Building(4L))).isNull()
		val flash = flashes.next(QuickBuildStatus.Building(4L), landed())

		assertThat(flash).isEqualTo(QuickBuildFlash.Recovery(R.string.quick_build_flash_recovered))
	}

	@Test
	fun `later successful builds do not flash`() {
		val flashes = QuickBuildFlashes()
		val broken = failed()
		flashes.next(QuickBuildStatus.Building(4L), broken)
		flashes.next(broken, QuickBuildStatus.Building(4L))
		flashes.next(QuickBuildStatus.Building(4L), landed(generation = 5L))

		// Every subsequent save also lands. None of them is news; a bar per save would sit over
		// the editor permanently.
		val second = flashes.next(landed(generation = 5L), QuickBuildStatus.Building(5L))
		val third = flashes.next(QuickBuildStatus.Building(5L), landed(generation = 6L))

		assertThat(second).isNull()
		assertThat(third).isNull()
	}

	@Test
	fun `a green build with no failure outstanding does not flash`() {
		val flashes = QuickBuildFlashes()

		val flash = flashes.next(QuickBuildStatus.Building(4L), landed())

		assertThat(flash).isNull()
	}

	@Test
	fun `a session settling after a failure does not claim a recovery`() {
		val flashes = QuickBuildFlashes()
		val broken = failed()
		flashes.next(QuickBuildStatus.Building(4L), broken)

		// No duration means no build landed - a warm compile or a restored session. Nothing was
		// fixed, so claiming success here would be a lie.
		val flash = flashes.next(QuickBuildStatus.Building(4L), landed(durationMillis = null))

		assertThat(flash).isNull()
	}

	@Test
	fun `a failed start raises no extra flash and drops any outstanding failure`() {
		val flashes = QuickBuildFlashes()
		val broken = failed()
		flashes.next(QuickBuildStatus.Building(4L), broken)

		// The manager's message channel already flashed the start failure; a second bar here
		// would double-report it.
		assertThat(flashes.next(broken, QuickBuildStatus.Hidden(lastStartFailed = true))).isNull()

		// And a later session's first landed build is not a recovery from the dead session's
		// failure.
		assertThat(flashes.next(QuickBuildStatus.Building(1L), landed(generation = 2L))).isNull()
	}

	@Test
	fun `a torn-down session drops the outstanding failure`() {
		val flashes = QuickBuildFlashes()
		val broken = failed()
		flashes.next(QuickBuildStatus.Building(4L), broken)

		assertThat(flashes.next(broken, QuickBuildStatus.Hidden())).isNull()

		// A later session's first landed build is not a recovery from a failure the user never
		// fixed - the failure left with the session it belonged to.
		val flash = flashes.next(QuickBuildStatus.Building(1L), landed(generation = 2L))

		assertThat(flash).isNull()
	}

	@Test
	fun `a deploy error does not flash`() {
		val flashes = QuickBuildFlashes()

		val flash =
			flashes.next(
				QuickBuildStatus.Building(4L),
				failed(SessionFailure.DeployError("Your app is not running.")),
			)

		assertThat(flash).isNull()
	}

	@Test
	fun `a proxy app crash does not flash - the crash notice already does`() {
		val flashes = QuickBuildFlashes()

		val flash =
			flashes.next(
				QuickBuildStatus.Building(4L),
				failed(SessionFailure.ProxyAppCrash("NPE in onCreate")),
			)

		assertThat(flash).isNull()
	}

	@Test
	fun `a deploy error does not arm a later recovery flash`() {
		val flashes = QuickBuildFlashes()
		val broken = failed(SessionFailure.DeployError("Your app is not running."))
		flashes.next(QuickBuildStatus.Building(4L), broken)

		// Nothing was flashed for it, so nothing needs clearing.
		val flash = flashes.next(QuickBuildStatus.Building(4L), landed())

		assertThat(flash).isNull()
	}

	@Test
	fun `in-flight and stale states do not flash`() {
		val flashes = QuickBuildFlashes()

		assertThat(flashes.next(QuickBuildStatus.Hidden(), QuickBuildStatus.Provisioning())).isNull()
		assertThat(flashes.next(QuickBuildStatus.Provisioning(), QuickBuildStatus.Building(4L))).isNull()
		assertThat(flashes.next(QuickBuildStatus.Building(4L), QuickBuildStatus.Reconnecting(4L))).isNull()
		assertThat(
			flashes.next(
				QuickBuildStatus.Reconnecting(4L),
				QuickBuildStatus.NeedsFullBuild(InvalidationReason.MANIFEST_CHANGED, 4L),
			),
		).isNull()
	}

	@Test
	fun `an unchanged status is not news`() {
		val flashes = QuickBuildFlashes()
		val broken = failed()

		assertThat(flashes.next(broken, broken)).isNull()
	}

	@Test
	fun `the ViewModel holds one flash history, so a rotation cannot re-flash a failure`() {
		// The history was an activity field. A configuration change rebuilds the activity, and
		// the rebuilt instance has never seen a failure - so the repeat guard resets and the
		// SAME unfixed failure flashes again, while the recovery this history arms is lost.
		// Held on the ViewModel it outlives the recreation, which is why this must stay a
		// stable `val` and not a getter that mints one per read.
		val viewModel = EditorViewModel()
		val failure = compileError()

		val first = viewModel.quickBuildFlashes
		assertThat(first.next(QuickBuildStatus.Building(4L), failed(failure)))
			.isEqualTo(QuickBuildFlash.Failure(R.string.quick_build_flash_failed))

		// What the activity sees after a rotation: the same ViewModel, so the same history.
		val afterRecreation = viewModel.quickBuildFlashes
		assertThat(afterRecreation).isSameInstanceAs(first)
		afterRecreation.next(failed(failure), QuickBuildStatus.Building(4L))
		assertThat(afterRecreation.next(QuickBuildStatus.Building(4L), failed(failure))).isNull()
	}
}
