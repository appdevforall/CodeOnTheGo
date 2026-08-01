package org.appdevforall.cotg.quickbuild.daemon.protocol

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test

/**
 * The codec's malformed-input taxonomy beyond ProtocolCodecTest: wrong TYPES (not just
 * missing fields) for ids, ops, strings and arrays. Every one must come back as
 * [ParseResult.Malformed] naming the offender - the daemon serves external callers, so an
 * unexpected shape must produce an actionable reply, never a throw or a misparse.
 */
class ProtocolCodecEdgeTest {
	private fun malformed(line: String): ParseResult.Malformed {
		val parsed = ProtocolCodec.parse(line)
		assertThat(parsed).isInstanceOf(ParseResult.Malformed::class.java)
		return parsed as ParseResult.Malformed
	}

	@Test
	fun `missing op is malformed but keeps the id for correlation`() {
		val parsed = malformed("""{"id": 5}""")

		assertThat(parsed.id).isEqualTo(5)
		assertThat(parsed.message).contains("op")
	}

	@Test
	fun `a non-string op is malformed, not misdispatched`() {
		assertThat(malformed("""{"id": 5, "op": 42}""").message).contains("op")
		assertThat(malformed("""{"id": 5, "op": {"nested": true}}""").message).contains("op")
	}

	@Test
	fun `a non-numeric id is malformed with the unknown id`() {
		assertThat(malformed("""{"id": "seven", "op": "ping"}""").id).isEqualTo(ParseResult.Malformed.UNKNOWN_ID)
		assertThat(malformed("""{"id": [7], "op": "ping"}""").id).isEqualTo(ParseResult.Malformed.UNKNOWN_ID)
		assertThat(malformed("""{"id": true, "op": "ping"}""").id).isEqualTo(ParseResult.Malformed.UNKNOWN_ID)
	}

	@Test
	fun `a missing required string names the field`() {
		val parsed = malformed("""{"id": 1, "op": "configure", "classpath": [], "outDir": "/out"}""")

		assertThat(parsed.id).isEqualTo(1)
		assertThat(parsed.message).contains("projectRoot")
	}

	@Test
	fun `a required string of the wrong type names the field`() {
		val parsed =
			malformed("""{"id": 4, "op": "relink", "resDirs": ["/res"], "manifest": 7}""")

		assertThat(parsed.message).contains("manifest")
	}

	@Test
	fun `a required list that is not an array names the field`() {
		val parsed = malformed("""{"id": 3, "op": "dex", "classesDirs": "/classes"}""")

		assertThat(parsed.id).isEqualTo(3)
		assertThat(parsed.message).contains("classesDirs")
		assertThat(parsed.message).contains("not an array")
	}

	@Test
	fun `a list containing a non-primitive element names the field`() {
		val parsed = malformed("""{"id": 3, "op": "dex", "classesDirs": [{"path": "/x"}]}""")

		assertThat(parsed.message).contains("classesDirs")
		assertThat(parsed.message).contains("non-string")
	}

	@Test
	fun `a missing required list names the field`() {
		val parsed = malformed("""{"id": 2, "op": "compile", "changedFiles": []}""")

		assertThat(parsed.message).contains("allSources")
	}

	@Test
	fun `an op that hash-collides with a real one is unknown, never misdispatched`() {
		// Each of these has the same String.hashCode() as a real op (the Java "Aa"/"BB"
		// collision family) but different text. Dispatch must compare the actual value,
		// not just the hash - a collision routed to a build op would run it with garbage.
		val collisions =
			listOf("dPnfigure", "dPmpile", "eFx", "sFlink", "qJng", "tIutdown")

		for (op in collisions) {
			val parsed = malformed("""{"id": 8, "op": "$op"}""")

			assertThat(parsed.id).isEqualTo(8)
			assertThat(parsed.message).contains("unknown op")
			assertThat(parsed.message).contains(op)
		}
	}

	@Test
	fun `encode writes boolean values as JSON booleans, not strings`() {
		val encoded = ProtocolCodec.encode(DaemonResponse.ok(6, mapOf("incremental" to true)))

		val root = JsonParser.parseString(encoded).asJsonObject
		assertThat(root.get("incremental").isJsonPrimitive).isTrue()
		assertThat(root.get("incremental").asJsonPrimitive.isBoolean).isTrue()
		assertThat(root.get("incremental").asBoolean).isTrue()
	}
}
