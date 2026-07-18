package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.junit.jupiter.api.Test

class KotlincDiagnosticsParserTest {
	@Test
	fun `parses path line column with explicit severity`() {
		val diagnostic =
			KotlincDiagnosticsParser.parse(
				"/p/src/B.kt:7:13: error: expecting an expression",
				Diagnostic.Severity.WARNING,
			)

		assertThat(diagnostic.file).isEqualTo("/p/src/B.kt")
		assertThat(diagnostic.line).isEqualTo(7)
		assertThat(diagnostic.column).isEqualTo(13)
		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostic.message).isEqualTo("expecting an expression")
	}

	@Test
	fun `parses renderer variant without severity word`() {
		val diagnostic =
			KotlincDiagnosticsParser.parse("/p/src/B.kt:7:13 unresolved reference: foo", Diagnostic.Severity.ERROR)

		assertThat(diagnostic.file).isEqualTo("/p/src/B.kt")
		assertThat(diagnostic.line).isEqualTo(7)
		assertThat(diagnostic.column).isEqualTo(13)
		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostic.message).isEqualTo("unresolved reference: foo")
	}

	@Test
	fun `file URI locations normalize to plain paths`() {
		val diagnostic =
			KotlincDiagnosticsParser.parse(
				"file:///p/src/Greeter.kt:4:41: error: Syntax error: Expecting an element.",
				Diagnostic.Severity.ERROR,
			)

		assertThat(diagnostic.file).isEqualTo("/p/src/Greeter.kt")
		assertThat(diagnostic.line).isEqualTo(4)
		assertThat(diagnostic.column).isEqualTo(41)
		assertThat(diagnostic.message).isEqualTo("Syntax error: Expecting an element.")
	}

	@Test
	fun `unparseable text degrades to a location-less diagnostic, never drops`() {
		val diagnostic = KotlincDiagnosticsParser.parse("something exploded internally", Diagnostic.Severity.ERROR)

		assertThat(diagnostic.file).isNull()
		assertThat(diagnostic.line).isNull()
		assertThat(diagnostic.message).isEqualTo("something exploded internally")
		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
	}
}
