package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * The static entry point every resource save calls ([GenerateSourcesDeferral.notifyResourceSaved]),
 * both directions: with Koin up the save routes into the singleton's deferral; with Koin down
 * (early startup, a torn-down graph) it must not throw and must fire the direct
 * `generateSources` fallback - a save that silently lost its build would leave the Java LSP's
 * R symbols stale with nothing on screen to say why.
 */
class GenerateSourcesDeferralEntryPointTest {
	@After
	fun tearDown() {
		stopKoin()
	}

	@Test
	fun `koin up routes the save into the registered deferral, not the fallback`() {
		var deferralBuilds = 0
		var fallbackBuilds = 0
		// No session attached, so the deferral runs its build immediately - which is how the
		// routing is observable without a session manager.
		val deferral =
			GenerateSourcesDeferral(
				scope = CoroutineScope(Dispatchers.Unconfined),
				runBuild = {
					deferralBuilds++
					true
				},
			)
		startKoin { modules(module { single { deferral } }) }

		GenerateSourcesDeferral.notifyResourceSaved { fallbackBuilds++ }

		assertThat(deferralBuilds).isEqualTo(1)
		assertThat(fallbackBuilds).isEqualTo(0)
	}

	@Test
	fun `koin down does not throw and fires the direct fallback`() {
		check(GlobalContext.getOrNull() == null) { "test needs Koin stopped" }
		var fallbackBuilds = 0

		GenerateSourcesDeferral.notifyResourceSaved { fallbackBuilds++ }

		assertThat(fallbackBuilds).isEqualTo(1)
	}

	@Test
	fun `koin up but no deferral registered still falls back instead of throwing`() {
		startKoin { modules(module {}) }
		var fallbackBuilds = 0

		GenerateSourcesDeferral.notifyResourceSaved { fallbackBuilds++ }

		assertThat(fallbackBuilds).isEqualTo(1)
	}
}
