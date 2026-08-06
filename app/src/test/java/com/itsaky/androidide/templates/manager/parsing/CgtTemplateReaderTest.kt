package com.itsaky.androidide.templates.manager.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// org.json.JSONObject needs Robolectric's shadow to run real logic instead of
// android.jar's "not mocked" stub.
@RunWith(RobolectricTestRunner::class)
class CgtTemplateReaderTest {
	/** Builds an in-memory .cgt (zip) from a map of entry path -> contents. */
	private fun cgt(entries: Map<String, String>): ByteArrayInputStream {
		val bytes = ByteArrayOutputStream()
		ZipOutputStream(bytes).use { zip ->
			for ((path, content) in entries) {
				zip.putNextEntry(ZipEntry(path))
				zip.write(content.toByteArray(Charsets.UTF_8))
				zip.closeEntry()
			}
		}
		return ByteArrayInputStream(bytes.toByteArray())
	}

	@Test
	fun readsSingleTemplateMetadata() {
		val input =
			cgt(
				mapOf(
					"pkg/template/template.json" to
						"""{"name":"Basic Activity","description":"Creates a new basic activity","version":"0.1"}""",
				),
			)
		val result = CgtTemplateReader.readTemplates(input)
		assertThat(result).hasSize(1)
		assertThat(result[0].name).isEqualTo("Basic Activity")
		assertThat(result[0].description).isEqualTo("Creates a new basic activity")
		assertThat(result[0].version).isEqualTo("0.1")
		assertThat(result[0].optionalTags).isEmpty()
	}

	@Test
	fun readsAllTemplatesInMultiTemplateArchive() {
		val input =
			cgt(
				mapOf(
					"a/template/template.json" to """{"name":"Empty","description":"e","version":"1.0"}""",
					"b/template/template.json" to """{"name":"Login","description":"l","version":"1.1"}""",
					"a/build.gradle.kts.peb" to "// not a template.json",
				),
			)
		val result = CgtTemplateReader.readTemplates(input)
		assertThat(result).hasSize(2)
		assertThat(result.map { it.name }.toSet()).isEqualTo(setOf("Empty", "Login"))
	}

	@Test
	fun parsesOptionalParametersAsTagWithIdentifier() {
		val input =
			cgt(
				mapOf(
					"pkg/template/template.json" to
						"""
						{
						"name":"T","description":"d","version":"1.0",
						"parameters": { "optional": {
							"language": {"identifier":"LANGUAGE"},
							"minsdk": {"identifier":"MIN_SDK"}
						} }
						}
						""".trimIndent(),
				),
			)
		val tags = CgtTemplateReader.readTemplates(input).single().optionalTags
		// org.json key iteration order isn't guaranteed, so compare as a set.
		assertThat(tags.toSet()).isEqualTo(setOf("language (LANGUAGE)", "minsdk (MIN_SDK)"))
	}

	@Test
	fun handlesUnquotedInnerKeys_asShippedByCore() {
		// The bundled core.cgt uses lenient JSON with unquoted inner keys; org.json accepts it.
		val input =
			cgt(
				mapOf(
					"BasicActivity/template/template.json" to
						"""
						{
						"name":"Basic Activity","description":"d","version":"0.1",
						"parameters": { "optional": { "language": {identifier: "LANGUAGE"} } }
						}
						""".trimIndent(),
				),
			)
		val template = CgtTemplateReader.readTemplates(input).single()
		assertThat(template.name).isEqualTo("Basic Activity")
		assertThat(template.optionalTags).isEqualTo(listOf("language (LANGUAGE)"))
	}

	@Test
	fun optionalTagWithoutIdentifierFallsBackToKey() {
		val input =
			cgt(
				mapOf(
					"pkg/template/template.json" to
						"""{"name":"T","description":"d","version":"1.0","parameters":{"optional":{"flag":{}}}}""",
				),
			)
		assertThat(CgtTemplateReader.readTemplates(input).single().optionalTags).isEqualTo(listOf("flag"))
	}

	@Test
	fun returnsEmptyWhenNoTemplateJson() {
		val input = cgt(mapOf("pkg/readme.txt" to "hello", "pkg/template/other.json" to "{}"))
		assertThat(CgtTemplateReader.readTemplates(input)).isEmpty()
	}
}
