package org.appdevforall.cotg.quickbuild.daemon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RequestRouterTest {
	private class RecordingHandlers : DaemonHandlers {
		val calls = mutableListOf<String>()
		var throwOnCompile: Exception? = null

		override fun configure(request: ConfigureRequest): DaemonResponse {
			calls += "configure"
			return DaemonResponse.ok(request.id)
		}

		override fun compile(request: CompileRequest): DaemonResponse {
			calls += "compile"
			throwOnCompile?.let { throw it }
			return DaemonResponse.ok(request.id, mapOf("classesDir" to "/out"))
		}

		override fun dex(request: DexRequest): DaemonResponse {
			calls += "dex"
			return DaemonResponse.ok(request.id)
		}

		override fun relink(request: RelinkRequest): DaemonResponse {
			calls += "relink"
			return DaemonResponse.ok(request.id)
		}
	}

	private val handlers = RecordingHandlers()
	private val router = RequestRouter(handlers)

	private fun configureRequest(id: Long = 1) = ConfigureRequest(id, "/p", emptyList(), "/out", "/aapt2", "/r8.jar", "/android.jar")

	@Test
	fun `build ops route to their handlers and reply`() {
		val configure = router.route(configureRequest(1))
		val compile = router.route(CompileRequest(2, emptyList(), emptyList()))
		val dex = router.route(DexRequest(3, emptyList()))
		val relink = router.route(RelinkRequest(4, emptyList(), "/M.xml"))

		assertThat(handlers.calls).containsExactly("configure", "compile", "dex", "relink").inOrder()
		for (routed in listOf(configure, compile, dex, relink)) {
			assertThat(routed).isInstanceOf(RequestRouter.Routed.Reply::class.java)
			assertThat(routed.response.ok).isTrue()
		}
		assertThat(compile.response.values["classesDir"]).isEqualTo("/out")
	}

	@Test
	fun `ping replies ok without touching handlers`() {
		val routed = router.route(PingRequest(5))

		assertThat(routed).isInstanceOf(RequestRouter.Routed.Reply::class.java)
		assertThat(routed.response).isEqualTo(DaemonResponse.ok(5))
		assertThat(handlers.calls).isEmpty()
	}

	@Test
	fun `shutdown replies ok and signals exit`() {
		val routed = router.route(ShutdownRequest(6))

		assertThat(routed).isInstanceOf(RequestRouter.Routed.ReplyThenExit::class.java)
		assertThat(routed.response).isEqualTo(DaemonResponse.ok(6))
	}

	@Test
	fun `a handler exception becomes an ok-false response, never a throw`() {
		handlers.throwOnCompile = IllegalStateException("compiler exploded")

		val routed = router.route(CompileRequest(7, emptyList(), emptyList()))

		assertThat(routed).isInstanceOf(RequestRouter.Routed.Reply::class.java)
		assertThat(routed.response.ok).isFalse()
		assertThat(routed.response.id).isEqualTo(7)
		val diagnostic = routed.response.diagnostics.single()
		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostic.message).contains("compiler exploded")
	}
}
