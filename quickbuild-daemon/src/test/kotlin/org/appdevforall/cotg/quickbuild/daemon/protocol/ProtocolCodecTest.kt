package org.appdevforall.cotg.quickbuild.daemon.protocol

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test

class ProtocolCodecTest {
	@Test
	fun `configure request round-trips every field`() {
		val line =
			"""{"id": 1, "op": "configure", "projectRoot": "/p", "classpath": ["/a.jar", "/b.jar"],
			"outDir": "/out", "aapt2": "/aapt2", "d8Jar": "/r8.jar", "androidJar": "/android.jar",
			"minApi": 26, "compilerPlugins": ["/compose-compiler-plugin.jar"]}""".replace("\n", "")

		val parsed = ProtocolCodec.parse(line)

		assertThat(parsed).isInstanceOf(ParseResult.Parsed::class.java)
		val request = (parsed as ParseResult.Parsed).request as ConfigureRequest
		assertThat(request.id).isEqualTo(1)
		assertThat(request.projectRoot).isEqualTo("/p")
		assertThat(request.classpath).containsExactly("/a.jar", "/b.jar").inOrder()
		assertThat(request.outDir).isEqualTo("/out")
		assertThat(request.aapt2).isEqualTo("/aapt2")
		assertThat(request.d8Jar).isEqualTo("/r8.jar")
		assertThat(request.androidJar).isEqualTo("/android.jar")
		assertThat(request.minApi).isEqualTo(26)
		assertThat(request.compilerPlugins).containsExactly("/compose-compiler-plugin.jar")
	}

	@Test
	fun `configure without minApi defaults to the v1 floor`() {
		val line =
			"""{"id": 1, "op": "configure", "projectRoot": "/p", "classpath": [],
			"outDir": "/out", "aapt2": "/aapt2", "d8Jar": "/r8.jar", "androidJar": "/android.jar"}""".replace("\n", "")

		val request = ((ProtocolCodec.parse(line)) as ParseResult.Parsed).request as ConfigureRequest

		assertThat(request.minApi).isEqualTo(30)
		assertThat(request.compilerPlugins).isEmpty()
	}

	@Test
	fun `compile dex relink ping shutdown parse to their request types`() {
		val compile =
			ProtocolCodec.parse("""{"id": 2, "op": "compile", "allSources": ["/A.kt"], "changedFiles": []}""")
		val dex = ProtocolCodec.parse("""{"id": 3, "op": "dex", "classesDirs": ["/classes"]}""")
		val relink = ProtocolCodec.parse("""{"id": 4, "op": "relink", "resDirs": ["/res"], "manifest": "/M.xml"}""")
		val ping = ProtocolCodec.parse("""{"id": 5, "op": "ping"}""")
		val shutdown = ProtocolCodec.parse("""{"id": 6, "op": "shutdown"}""")

		assertThat((compile as ParseResult.Parsed).request)
			.isEqualTo(CompileRequest(2, listOf("/A.kt"), emptyList()))
		assertThat((dex as ParseResult.Parsed).request).isEqualTo(DexRequest(3, listOf("/classes")))
		assertThat((relink as ParseResult.Parsed).request)
			.isEqualTo(RelinkRequest(4, listOf("/res"), "/M.xml"))
		assertThat((ping as ParseResult.Parsed).request).isEqualTo(PingRequest(5))
		assertThat((shutdown as ParseResult.Parsed).request).isEqualTo(ShutdownRequest(6))
	}

	@Test
	fun `invalid JSON is malformed with unknown id, never a throw`() {
		val parsed = ProtocolCodec.parse("this is not json {")

		assertThat(parsed).isInstanceOf(ParseResult.Malformed::class.java)
		assertThat((parsed as ParseResult.Malformed).id).isEqualTo(ParseResult.Malformed.UNKNOWN_ID)
	}

	@Test
	fun `missing id is malformed`() {
		val parsed = ProtocolCodec.parse("""{"op": "ping"}""")

		assertThat(parsed).isInstanceOf(ParseResult.Malformed::class.java)
	}

	@Test
	fun `unknown op is malformed but keeps the id for correlation`() {
		val parsed = ProtocolCodec.parse("""{"id": 9, "op": "transmogrify"}""")

		assertThat(parsed).isInstanceOf(ParseResult.Malformed::class.java)
		assertThat((parsed as ParseResult.Malformed).id).isEqualTo(9)
		assertThat(parsed.message).contains("transmogrify")
	}

	@Test
	fun `missing required field is malformed with the field named`() {
		val parsed = ProtocolCodec.parse("""{"id": 2, "op": "compile", "allSources": ["/A.kt"]}""")

		assertThat(parsed).isInstanceOf(ParseResult.Malformed::class.java)
		assertThat((parsed as ParseResult.Malformed).message).contains("changedFiles")
	}

	@Test
	fun `non-string element in a string list is malformed`() {
		val parsed = ProtocolCodec.parse("""{"id": 3, "op": "dex", "classesDirs": ["/ok", 42]}""")

		assertThat(parsed).isInstanceOf(ParseResult.Malformed::class.java)
	}

	@Test
	fun `array root is malformed`() {
		val parsed = ProtocolCodec.parse("""[1, 2, 3]""")

		assertThat(parsed).isInstanceOf(ParseResult.Malformed::class.java)
	}

	@Test
	fun `ok response encodes flat values`() {
		val encoded =
			ProtocolCodec.encode(
				DaemonResponse.ok(7, mapOf("classesDir" to "/out/classes", "durationMillis" to 123L)),
			)

		val root = JsonParser.parseString(encoded).asJsonObject
		assertThat(root.get("id").asLong).isEqualTo(7)
		assertThat(root.get("ok").asBoolean).isTrue()
		assertThat(root.get("classesDir").asString).isEqualTo("/out/classes")
		assertThat(root.get("durationMillis").asLong).isEqualTo(123)
		assertThat(root.has("diagnostics")).isFalse()
	}

	@Test
	fun `ok response encodes list values as JSON arrays - the classesChanged shape`() {
		val encoded =
			ProtocolCodec.encode(
				DaemonResponse.ok(
					8,
					mapOf("classesChanged" to listOf("demo/Greeter.class", "demo/Outer\$Inner.class")),
				),
			)

		val root = JsonParser.parseString(encoded).asJsonObject
		assertThat(root.get("classesChanged").isJsonArray).isTrue()
		assertThat(root.getAsJsonArray("classesChanged").map { it.asString })
			.containsExactly("demo/Greeter.class", "demo/Outer\$Inner.class")
			.inOrder()
	}

	@Test
	fun `failure response encodes diagnostics in the protocol shape`() {
		val encoded =
			ProtocolCodec.encode(
				DaemonResponse.failure(
					8,
					listOf(
						Diagnostic(Diagnostic.Severity.ERROR, "expecting an expression", "/p/B.kt", 7, 13),
						Diagnostic(Diagnostic.Severity.WARNING, "no location"),
					),
				),
			)

		val root = JsonParser.parseString(encoded).asJsonObject
		assertThat(root.get("ok").asBoolean).isFalse()
		val diagnostics = root.getAsJsonArray("diagnostics")
		assertThat(diagnostics.size()).isEqualTo(2)
		val first = diagnostics[0].asJsonObject
		assertThat(first.get("severity").asString).isEqualTo("ERROR")
		assertThat(first.get("message").asString).isEqualTo("expecting an expression")
		assertThat(first.get("file").asString).isEqualTo("/p/B.kt")
		assertThat(first.get("line").asInt).isEqualTo(7)
		assertThat(first.get("column").asInt).isEqualTo(13)
		val second = diagnostics[1].asJsonObject
		assertThat(second.has("file")).isFalse()
		assertThat(second.has("line")).isFalse()
	}

	@Test
	fun `encoded response is a single line even with newlines in messages`() {
		val encoded =
			ProtocolCodec.encode(
				DaemonResponse.failure(9, listOf(Diagnostic(Diagnostic.Severity.ERROR, "line one\nline two"))),
			)

		assertThat(encoded).doesNotContain("\n")
		val root = JsonParser.parseString(encoded).asJsonObject
		val message =
			root
				.getAsJsonArray("diagnostics")[0]
				.asJsonObject
				.get("message")
				.asString
		assertThat(message).isEqualTo("line one\nline two")
	}
}
