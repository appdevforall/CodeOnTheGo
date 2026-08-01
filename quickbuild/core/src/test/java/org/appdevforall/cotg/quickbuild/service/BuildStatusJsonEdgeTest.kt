package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import org.junit.jupiter.api.Test

/**
 * Partial-location encodings of [BuildStatusJson.buildFailed]: each location field is
 * independently optional (kotlinc reports file+line without column for some
 * diagnostics; aapt2 often reports file only), and an absent field must be omitted,
 * not invented - the runtime overlay renders exactly what is present.
 */
class BuildStatusJsonEdgeTest {
	@Test
	fun `file and line without a column encode both and omit the column`() {
		val json =
			JsonParser
				.parseString(
					BuildStatusJson.buildFailed(
						listOf(
							BuildDiagnostic(
								severity = BuildDiagnostic.Severity.ERROR,
								message = "expecting an element",
								file = "app/src/main/java/Foo.kt",
								line = 12,
								column = null,
							),
						),
					),
				).asJsonObject

		assertThat(json.get("file").asString).isEqualTo("app/src/main/java/Foo.kt")
		assertThat(json.get("line").asString).isEqualTo("12")
		assertThat(json.has("column")).isFalse()
	}

	@Test
	fun `a file-only diagnostic encodes the file and omits line and column`() {
		val json =
			JsonParser
				.parseString(
					BuildStatusJson.buildFailed(
						listOf(
							BuildDiagnostic(
								severity = BuildDiagnostic.Severity.ERROR,
								message = "resource linking failed",
								file = "app/src/main/res/values/strings.xml",
							),
						),
					),
				).asJsonObject

		assertThat(json.get("file").asString).isEqualTo("app/src/main/res/values/strings.xml")
		assertThat(json.has("line")).isFalse()
		assertThat(json.has("column")).isFalse()
	}
}
