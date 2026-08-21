package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonHandlers
import org.appdevforall.cotg.quickbuild.daemon.protocol.RequestRouter
import org.appdevforall.cotg.quickbuild.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.protocol.RelinkRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter

/**
 * The loop's own backstop, outside the router's: parse and encode both run on request-sized data
 * and neither was wrapped, so a throw from either exited the JVM and CoGo reported daemon death.
 *
 * Driven through encode, because a value whose `toString` throws is a deterministic way to break
 * it - no real memory pressure, no pathological input, and it exercises the exact arm a compile
 * response with a huge changed-class list would hit.
 */
class DaemonLoopErrorTest {
	/** A response value the codec must stringify, which throws instead. */
	private class ExplodingValue(
		private val boom: () -> Nothing,
	) {
		override fun toString(): String = boom()
	}

	private class RespondingHandlers(
		private val response: (Long) -> DaemonResponse,
	) : DaemonHandlers {
		override fun configure(request: ConfigureRequest): DaemonResponse = response(request.id)

		override fun compile(request: CompileRequest): DaemonResponse = response(request.id)

		override fun dex(request: DexRequest): DaemonResponse = response(request.id)

		override fun relink(request: RelinkRequest): DaemonResponse = response(request.id)
	}

	private fun serve(
		boom: () -> Nothing,
		vararg lines: String,
	): List<String> {
		val output = StringWriter()
		DaemonMain.serve(
			input = BufferedReader(StringReader(lines.joinToString("\n"))),
			output = output,
			router =
				RequestRouter(
					RespondingHandlers { id ->
						DaemonResponse.ok(id, mapOf("classesDir" to ExplodingValue(boom)))
					},
				),
		)
		return output.toString().lines().filter { it.isNotBlank() }
	}

	private val compile = """{"id": 41, "op": "compile", "allSources": [], "changedFiles": []}"""
	private val ping = """{"id": 42, "op": "ping"}"""

	@Test
	fun `an out-of-memory while encoding replies ok-false on that id and keeps serving`() {
		val responses = serve({ throw OutOfMemoryError("Java heap space") }, compile, ping)

		assertThat(responses).hasSize(2)
		val failed = JsonParser.parseString(responses[0]).asJsonObject
		assertThat(failed.get("ok").asBoolean).isFalse()
		assertThat(failed.get("id").asLong).isEqualTo(41)
		val message =
			failed
				.getAsJsonArray("diagnostics")
				.single()
				.asJsonObject
				.get("message")
				.asString
		assertThat(message).contains("ran out of memory")

		// The half that matters: the loop is still alive to answer the next request.
		val served = JsonParser.parseString(responses[1]).asJsonObject
		assertThat(served.get("ok").asBoolean).isTrue()
		assertThat(served.get("id").asLong).isEqualTo(42)
	}

	@Test
	fun `a stack overflow while encoding replies ok-false and keeps serving`() {
		val responses = serve({ throw StackOverflowError() }, compile, ping)

		assertThat(responses).hasSize(2)
		assertThat(
			JsonParser
				.parseString(responses[0])
				.asJsonObject
				.get("ok")
				.asBoolean,
		).isFalse()
		assertThat(
			JsonParser
				.parseString(responses[1])
				.asJsonObject
				.get("ok")
				.asBoolean,
		).isTrue()
	}

	@Test
	fun `a fatal error still ends the loop, so the exit contract keeps its teeth`() {
		assertThrows<NoClassDefFoundError> {
			serve({ throw NoClassDefFoundError("com/example/Gone") }, compile, ping)
		}
	}
}
