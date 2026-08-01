package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import org.junit.jupiter.api.Test

class BuildStatusJsonTest {
	private fun parse(json: String) = JsonParser.parseString(json).asJsonObject

	private fun error(
		message: String,
		file: String? = null,
		line: Int? = null,
		column: Int? = null,
	) = BuildDiagnostic(BuildDiagnostic.Severity.ERROR, message, file, line, column)

	@Test
	fun `encodes the first error with string-only values`() {
		val json =
			BuildStatusJson.buildFailed(
				listOf(error("Unresolved reference: foo", "/p/src/Foo.kt", 12, 5)),
			)

		val obj = parse(json)
		assertThat(obj.get("kind").asString).isEqualTo("build_failed")
		assertThat(obj.get("file").asString).isEqualTo("/p/src/Foo.kt")
		// String-typed on purpose: the runtime's MiniJson reads only strings.
		assertThat(obj.get("line").asJsonPrimitive.isString).isTrue()
		assertThat(obj.get("line").asString).isEqualTo("12")
		assertThat(obj.get("column").asString).isEqualTo("5")
		assertThat(obj.get("message").asString).isEqualTo("Unresolved reference: foo")
		assertThat(obj.has("moreErrors")).isFalse()
	}

	@Test
	fun `keeps only the first line of a multi-line message`() {
		val json =
			BuildStatusJson.buildFailed(
				listOf(error("first line\nsecond line\nthird", "/p/A.kt", 1)),
			)

		assertThat(parse(json).get("message").asString).isEqualTo("first line")
	}

	@Test
	fun `prefers the first ERROR over earlier warnings and counts the rest`() {
		val warning = BuildDiagnostic(BuildDiagnostic.Severity.WARNING, "meh", "/p/W.kt", 1)
		val json =
			BuildStatusJson.buildFailed(
				listOf(warning, error("real problem", "/p/E.kt", 7), error("another", "/p/E2.kt", 9)),
			)

		val obj = parse(json)
		assertThat(obj.get("file").asString).isEqualTo("/p/E.kt")
		assertThat(obj.get("message").asString).isEqualTo("real problem")
		assertThat(obj.get("moreErrors").asString).isEqualTo("1")
	}

	@Test
	fun `falls back to the first diagnostic when there is no ERROR`() {
		val warning = BuildDiagnostic(BuildDiagnostic.Severity.WARNING, "warn only", "/p/W.kt", 2)
		val json = BuildStatusJson.buildFailed(listOf(warning))

		val obj = parse(json)
		assertThat(obj.get("message").asString).isEqualTo("warn only")
		assertThat(obj.has("moreErrors")).isFalse()
	}

	@Test
	fun `omits absent location fields instead of inventing them`() {
		val json = BuildStatusJson.buildFailed(listOf(error("no location")))

		val obj = parse(json)
		assertThat(obj.has("file")).isFalse()
		assertThat(obj.has("line")).isFalse()
		assertThat(obj.has("column")).isFalse()
	}

	@Test
	fun `empty diagnostics still encode a valid failure`() {
		val obj = parse(BuildStatusJson.buildFailed(emptyList()))
		assertThat(obj.get("kind").asString).isEqualTo("build_failed")
	}

	@Test
	fun `buildOk encodes only the kind`() {
		val obj = parse(BuildStatusJson.buildOk())
		assertThat(obj.get("kind").asString).isEqualTo("build_ok")
		assertThat(obj.size()).isEqualTo(1)
	}

	@Test
	fun `building encodes the kind and running generation as strings`() {
		val obj = parse(BuildStatusJson.building(5L))
		assertThat(obj.get("kind").asString).isEqualTo("building")
		assertThat(obj.get("runningGeneration").asJsonPrimitive.isString).isTrue()
		assertThat(obj.get("runningGeneration").asString).isEqualTo("5")
	}

	@Test
	fun `building encodes a zero generation the same way as any other`() {
		val obj = parse(BuildStatusJson.building(0L))
		assertThat(obj.get("runningGeneration").asString).isEqualTo("0")
	}

	@Test
	fun `wire format round-trips through the runtime parser contract`() {
		// Gson must escape what MiniJson unescapes - quotes, backslashes, newlines.
		val json =
			BuildStatusJson.buildFailed(
				listOf(error("expecting '\"' after \\ in C:\\path", "/p/Q.kt", 3)),
			)

		assertThat(parse(json).get("message").asString)
			.isEqualTo("expecting '\"' after \\ in C:\\path")
	}
}
