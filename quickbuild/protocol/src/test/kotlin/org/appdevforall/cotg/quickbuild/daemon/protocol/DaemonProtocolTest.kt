package org.appdevforall.cotg.quickbuild.daemon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The wire-model logic: stats round-trips, response helpers, malformed-input ids. */
class DaemonProtocolTest {
	@Test
	fun `CompileStats round-trips through toValues and fromValues`() {
		val stats =
			CompileStats(
				preSnapMillis = 11,
				postSnapMillis = 22,
				javaAbiSnapMillis = 33,
				allSources = 40,
				kotlinToCompile = 3,
				javaSources = 2,
				changedClasses = 5,
				compileOrdinal = 7,
			)

		val values = stats.toValues()
		val restored = CompileStats.fromValues { key -> (values[key] as? Number)?.toLong() }

		assertThat(restored).isEqualTo(stats)
	}

	@Test
	fun `CompileStats fromValues is null when no key is present`() {
		// A response from a daemon predating the stats fields must read back as
		// "not measured", not as a zero-filled row that reads as "measured, free".
		assertThat(CompileStats.fromValues { null }).isNull()
	}

	@Test
	fun `CompileStats fromValues defaults an individually missing key to zero`() {
		val restored =
			CompileStats.fromValues { key ->
				if (key == CompileStats.KEY_COMPILE_ORDINAL) 3L else null
			}

		assertThat(restored).isEqualTo(CompileStats(compileOrdinal = 3))
	}

	@Test
	fun `DexStats round-trips through toValues and fromValues`() {
		val stats = DexStats(classFiles = 464, classBytes = 1_234_567)

		val values = stats.toValues()
		val restored = DexStats.fromValues { key -> (values[key] as? Number)?.toLong() }

		assertThat(restored).isEqualTo(stats)
	}

	@Test
	fun `DexStats fromValues is null only when both keys are absent`() {
		assertThat(DexStats.fromValues { null }).isNull()

		val filesOnly =
			DexStats.fromValues { key ->
				if (key == DexStats.KEY_CLASS_FILES) 9L else null
			}

		assertThat(filesOnly).isEqualTo(DexStats(classFiles = 9, classBytes = 0))
	}

	@Test
	fun `ok builds a success response carrying the values and no diagnostics`() {
		val response = DaemonResponse.ok(5, mapOf("classesDir" to "/out/classes"))

		assertThat(response.id).isEqualTo(5)
		assertThat(response.ok).isTrue()
		assertThat(response.values).containsExactly("classesDir", "/out/classes")
		assertThat(response.diagnostics).isEmpty()
	}

	@Test
	fun `failure from a message wraps it as a single ERROR diagnostic`() {
		val response = DaemonResponse.failure(9, "aapt2 exited 1")

		assertThat(response.id).isEqualTo(9)
		assertThat(response.ok).isFalse()
		assertThat(response.values).isEmpty()
		assertThat(response.diagnostics)
			.containsExactly(Diagnostic(Diagnostic.Severity.ERROR, "aapt2 exited 1"))
	}

	@Test
	fun `failure from diagnostics keeps them verbatim`() {
		val diagnostics =
			listOf(
				Diagnostic(Diagnostic.Severity.ERROR, "unresolved reference", file = "A.kt", line = 3, column = 7),
				Diagnostic(Diagnostic.Severity.WARNING, "unused variable"),
			)

		val response = DaemonResponse.failure(2, diagnostics)

		assertThat(response.ok).isFalse()
		assertThat(response.diagnostics).isEqualTo(diagnostics)
	}
}
