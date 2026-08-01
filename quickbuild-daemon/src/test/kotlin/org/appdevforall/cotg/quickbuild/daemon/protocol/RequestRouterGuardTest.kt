package org.appdevforall.cotg.quickbuild.daemon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The exception backstop on EVERY build op, not just compile (RequestRouterTest covers
 * that one): `guarded` is inline, so each op's call site carries its own copy of the
 * catch - a throw escaping any one of them would kill the daemon process, breaking the
 * README contract that the daemon only exits on shutdown, EOF, or a fatal internal error.
 */
class RequestRouterGuardTest {
	private class ThrowingHandlers(
		private val boom: Exception,
	) : DaemonHandlers {
		override fun configure(request: ConfigureRequest): DaemonResponse = throw boom

		override fun compile(request: CompileRequest): DaemonResponse = throw boom

		override fun dex(request: DexRequest): DaemonResponse = throw boom

		override fun relink(request: RelinkRequest): DaemonResponse = throw boom
	}

	@Test
	fun `an exception from any build op becomes an ok-false reply carrying that op's id`() {
		val router = RequestRouter(ThrowingHandlers(IllegalStateException("tool exploded")))
		val requests =
			listOf(
				ConfigureRequest(21, "/p", emptyList(), "/out"),
				CompileRequest(22, emptyList(), emptyList()),
				DexRequest(23, emptyList()),
				RelinkRequest(24, emptyList(), "/M.xml"),
			)

		for (request in requests) {
			val routed = router.route(request)

			assertThat(routed).isInstanceOf(RequestRouter.Routed.Reply::class.java)
			assertThat(routed.response.ok).isFalse()
			assertThat(routed.response.id).isEqualTo(request.id)
			val diagnostic = routed.response.diagnostics.single()
			assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
			assertThat(diagnostic.message).contains("IllegalStateException")
			assertThat(diagnostic.message).contains("tool exploded")
		}
	}
}
