package com.itsaky.androidide.templates.manager.models

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class CgtFileItemTest {
	private fun item(
		name: String,
		templates: List<TemplateMetadata> = listOf(TemplateMetadata("T", "d", "1.0")),
		provenance: TemplateProvenance = TemplateProvenance.USER,
	) = CgtFileItem(
		file = File("/tmp/$name"),
		name = name,
		templates = templates,
		installed = false,
		provenance = provenance,
	)

	@Test
	fun displayName_stripsCgtExtension() {
		assertThat(item("core.cgt").displayName).isEqualTo("core")
		assertThat(item("core.CGT").displayName).isEqualTo("core") // case-insensitive
	}

	@Test
	fun displayName_leavesOtherNamesUnchanged() {
		assertThat(item("core").displayName).isEqualTo("core")
		assertThat(item("my.template.cgt").displayName).isEqualTo("my.template.cgt".dropLast(4))
		assertThat(item("readme.txt").displayName).isEqualTo("readme.txt")
	}

	@Test
	fun primaryTemplate_isFirst_orEmptyFallback() {
		val a = TemplateMetadata("A", "da", "1.0")
		val b = TemplateMetadata("B", "db", "2.0")
		assertThat(item("x.cgt", listOf(a, b)).primaryTemplate).isEqualTo(a)

		val empty = item("x.cgt", emptyList()).primaryTemplate
		assertThat(empty.name).isEmpty()
		assertThat(empty.version).isEmpty()
	}

	@Test
	fun hasMultipleTemplates_reflectsCount() {
		assertThat(item("x.cgt", listOf(TemplateMetadata("A", "", "1"))).hasMultipleTemplates).isFalse()
		assertThat(
			item("x.cgt", listOf(TemplateMetadata("A", "", "1"), TemplateMetadata("B", "", "1")))
				.hasMultipleTemplates,
		).isTrue()
		assertThat(item("x.cgt", emptyList()).hasMultipleTemplates).isFalse()
	}

	@Test
	fun versionLabel_prefixesWithV() {
		assertThat(versionLabel("1.0")).isEqualTo("v1.0")
		assertThat(versionLabel("0.1")).isEqualTo("v0.1")
		assertThat(versionLabel("1.2.3")).isEqualTo("v1.2.3")
	}

	@Test
	fun versionLabel_truncatesMoreThanThreeSegments() {
		// Only the first three dot-separated segments are kept (matches the host Plugin Manager).
		assertThat(versionLabel("1.0.0-build.20260101")).isEqualTo("v1.0.0-build...")
		assertThat(versionLabel("1.2.3.4")).isEqualTo("v1.2.3...")
	}

	@Test
	fun versionLabel_blankBecomesEmpty() {
		assertThat(versionLabel("")).isEmpty()
		assertThat(versionLabel("   ")).isEmpty()
	}
}
