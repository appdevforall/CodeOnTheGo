package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.protocol.Diagnostic
import org.junit.jupiter.api.Test

/**
 * The severity-word override in the direction KotlincDiagnosticsParserTest doesn't pin, plus
 * how a multi-line message is split between location and body.
 */
class KotlincDiagnosticsParserEdgeTest {
	@Test
	fun `an explicit warning prefix downgrades a message from the error channel`() {
		// Some renderers deliver warnings through the error() logger channel; the text's
		// own "warning:" must win, or the client would fail builds over warnings.
		val diagnostic =
			KotlincDiagnosticsParser.parse(
				"/p/src/A.kt:3:5: warning: unused variable 'x'",
				Diagnostic.Severity.ERROR,
			)

		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.WARNING)
		assertThat(diagnostic.message).isEqualTo("unused variable 'x'")
		assertThat(diagnostic.file).isEqualTo("/p/src/A.kt")
		assertThat(diagnostic.line).isEqualTo(3)
		assertThat(diagnostic.column).isEqualTo(5)
	}

	@Test
	fun `a location line keeps its multi-line body in the message`() {
		// kotlinc renders inference failures as a headline plus indented candidate lines; the
		// body is what makes the error actionable, so it must survive on the diagnostic.
		val diagnostic =
			KotlincDiagnosticsParser.parse(
				"/p/src/A.kt:3:5: error: none of the following candidates is applicable:\n" +
					"    fun of(value: Int): Wrapper\n" +
					"    fun of(value: String): Wrapper",
				Diagnostic.Severity.WARNING,
			)

		assertThat(diagnostic.file).isEqualTo("/p/src/A.kt")
		assertThat(diagnostic.line).isEqualTo(3)
		assertThat(diagnostic.column).isEqualTo(5)
		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostic.message).startsWith("none of the following candidates is applicable:")
		assertThat(diagnostic.message).contains("fun of(value: String): Wrapper")
	}

	@Test
	fun `a message whose location is on a later line keeps its first line`() {
		// Matching the location across newlines swallowed the headline into the file group,
		// producing a path with a newline in it and dropping the primary error text.
		val diagnostic =
			KotlincDiagnosticsParser.parse(
				"inference failure: candidate not applicable\n/p/src/A.kt:3:5: error: boom",
				Diagnostic.Severity.ERROR,
			)

		assertThat(diagnostic.message).contains("inference failure: candidate not applicable")
		assertThat(diagnostic.file).isNull()
		assertThat(diagnostic.line).isNull()
		assertThat(diagnostic.column).isNull()
	}

	@Test
	fun `a compiler crash dump keeps its headline and its stack trace`() {
		val diagnostic =
			KotlincDiagnosticsParser.parse(
				"e: java.lang.AssertionError: no descriptor for Foo\n" +
					"\tat org.jetbrains.kotlin.Fir.resolve(Fir.kt:120)",
				Diagnostic.Severity.ERROR,
			)

		assertThat(diagnostic.file).isNull()
		assertThat(diagnostic.message).startsWith("e: java.lang.AssertionError: no descriptor for Foo")
		assertThat(diagnostic.message).contains("Fir.kt:120")
	}

	@Test
	fun `a windows path parses despite the drive-letter colon`() {
		val diagnostic =
			KotlincDiagnosticsParser.parse("""C:\src\A.kt:3:5: error: boom""", Diagnostic.Severity.WARNING)

		assertThat(diagnostic.file).isEqualTo("""C:\src\A.kt""")
		assertThat(diagnostic.line).isEqualTo(3)
		assertThat(diagnostic.column).isEqualTo(5)
		assertThat(diagnostic.message).isEqualTo("boom")
	}
}
