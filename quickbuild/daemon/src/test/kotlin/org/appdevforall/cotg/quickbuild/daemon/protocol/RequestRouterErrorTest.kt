package org.appdevforall.cotg.quickbuild.daemon.protocol

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.protocol.DaemonRequest
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.protocol.Diagnostic
import org.appdevforall.cotg.quickbuild.protocol.RelinkRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The [Error] half of the backstop, which the [Exception] cases in RequestRouterGuardTest do not
 * cover: the compiler runs in the daemon's own JVM, so an out-of-memory or a parser stack
 * overflow on the user's source would otherwise leave `route`, leave `main`, and exit the
 * process. CoGo reads that as daemon death and restarts, so the same save would kill the same
 * daemon forever with no diagnostic ever rendered.
 */
class RequestRouterErrorTest {
	private class ThrowingHandlers(
		private val boom: () -> Nothing,
	) : DaemonHandlers {
		override fun configure(request: ConfigureRequest): DaemonResponse = boom()

		override fun compile(request: CompileRequest): DaemonResponse = boom()

		override fun dex(request: DexRequest): DaemonResponse = boom()

		override fun relink(request: RelinkRequest): DaemonResponse = boom()
	}

	private fun everyBuildOp(): List<DaemonRequest> =
		listOf(
			ConfigureRequest(31, "/p", emptyList(), "/out"),
			CompileRequest(32, emptyList(), emptyList()),
			DexRequest(33, emptyList()),
			RelinkRequest(34, emptyList(), "/M.xml"),
		)

	@Test
	fun `an out-of-memory from any build op becomes an ok-false reply naming the memory`() {
		val router = RequestRouter(ThrowingHandlers { throw OutOfMemoryError("Java heap space") })

		for (request in everyBuildOp()) {
			val routed = router.route(request)

			assertThat(routed).isInstanceOf(RequestRouter.Routed.Reply::class.java)
			assertThat(routed.response.ok).isFalse()
			assertThat(routed.response.id).isEqualTo(request.id)
			val diagnostic = routed.response.diagnostics.single()
			assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
			assertThat(diagnostic.message).contains("ran out of memory")
			// The point of naming the condition: "internal error" would tell the user nothing
			// they could act on, and this is a build outcome they can.
			assertThat(diagnostic.message).doesNotContain("internal")
		}
	}

	@Test
	fun `a stack overflow from any build op becomes an ok-false reply naming the nesting`() {
		val router = RequestRouter(ThrowingHandlers { throw StackOverflowError() })

		for (request in everyBuildOp()) {
			val routed = router.route(request)

			assertThat(routed).isInstanceOf(RequestRouter.Routed.Reply::class.java)
			assertThat(routed.response.ok).isFalse()
			assertThat(routed.response.id).isEqualTo(request.id)
			assertThat(
				routed.response.diagnostics
					.single()
					.message,
			).contains("nests too")
		}
	}

	@Test
	fun `a linkage error still escapes, because that one really is fatal`() {
		val router = RequestRouter(ThrowingHandlers { throw NoClassDefFoundError("com/example/Gone") })

		assertThrows<NoClassDefFoundError> {
			router.route(CompileRequest(35, emptyList(), emptyList()))
		}
	}

	@Test
	fun `the failure classifier splits request failures from fatal ones`() {
		assertThat(RequestRouter.isRequestFailure(IllegalStateException("tool exploded"))).isTrue()
		assertThat(RequestRouter.isRequestFailure(OutOfMemoryError("Java heap space"))).isTrue()
		assertThat(RequestRouter.isRequestFailure(StackOverflowError())).isTrue()

		assertThat(RequestRouter.isRequestFailure(NoClassDefFoundError("com/example/Gone"))).isFalse()
		assertThat(RequestRouter.isRequestFailure(UnsatisfiedLinkError("libd8"))).isFalse()
		assertThat(RequestRouter.isRequestFailure(InternalError("vm"))).isFalse()
	}

	@Test
	fun `an ordinary exception keeps its class and message, which the two Errors replace`() {
		assertThat(RequestRouter.describe(IllegalStateException("tool exploded")))
			.isEqualTo("internal: IllegalStateException: tool exploded")
		assertThat(RequestRouter.describe(OutOfMemoryError("Java heap space")))
			.doesNotContain("Java heap space")
	}
}
