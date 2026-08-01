package org.appdevforall.cotg.quickbuild.daemon.compile

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.junit.jupiter.api.Test

/** The severity-word override in the direction KotlincDiagnosticsParserTest doesn't pin. */
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
}
