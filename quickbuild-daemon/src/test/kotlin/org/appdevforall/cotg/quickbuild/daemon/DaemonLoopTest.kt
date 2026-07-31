package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.daemon.protocol.RequestRouter
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter

/** Drives [DaemonMain.serve] over in-memory streams: the protocol loop end to end. */
class DaemonLoopTest {
	private fun serve(vararg lines: String): List<String> {
		val output = StringWriter()
		DaemonMain.serve(
			input = BufferedReader(StringReader(lines.joinToString("\n"))),
			output = output,
			router = RequestRouter(DaemonService(log = {})),
		)
		return output.toString().lines().filter { it.isNotBlank() }
	}

	@Test
	fun `ping round-trips over the wire`() {
		val responses = serve("""{"id": 1, "op": "ping"}""")

		assertThat(responses).hasSize(1)
		val root = JsonParser.parseString(responses[0]).asJsonObject
		assertThat(root.get("id").asLong).isEqualTo(1)
		assertThat(root.get("ok").asBoolean).isTrue()
	}

	@Test
	fun `malformed request replies ok-false and the loop keeps serving`() {
		val responses =
			serve(
				"not json at all",
				"""{"id": 2, "op": "ping"}""",
			)

		assertThat(responses).hasSize(2)
		val malformed = JsonParser.parseString(responses[0]).asJsonObject
		assertThat(malformed.get("ok").asBoolean).isFalse()
		assertThat(malformed.get("id").asLong).isEqualTo(-1)
		val ping = JsonParser.parseString(responses[1]).asJsonObject
		assertThat(ping.get("ok").asBoolean).isTrue()
	}

	@Test
	fun `blank lines are skipped without a response`() {
		val responses = serve("", "   ", """{"id": 3, "op": "ping"}""")

		assertThat(responses).hasSize(1)
	}

	@Test
	fun `shutdown replies then stops serving later requests`() {
		val responses =
			serve(
				"""{"id": 4, "op": "shutdown"}""",
				"""{"id": 5, "op": "ping"}""",
			)

		assertThat(responses).hasSize(1)
		val root = JsonParser.parseString(responses[0]).asJsonObject
		assertThat(root.get("id").asLong).isEqualTo(4)
		assertThat(root.get("ok").asBoolean).isTrue()
	}

	@Test
	fun `EOF ends the loop cleanly after serving everything`() {
		val responses =
			serve(
				"""{"id": 6, "op": "ping"}""",
				"""{"id": 7, "op": "compile", "allSources": [], "changedFiles": []}""",
			)

		// compile before configure: served (ok:false), then EOF returned normally.
		assertThat(responses).hasSize(2)
		val compile = JsonParser.parseString(responses[1]).asJsonObject
		assertThat(compile.get("ok").asBoolean).isFalse()
	}
}
