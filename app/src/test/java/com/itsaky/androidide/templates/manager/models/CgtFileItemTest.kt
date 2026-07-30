package com.itsaky.androidide.templates.manager.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
		assertEquals("core", item("core.cgt").displayName)
		assertEquals("core", item("core.CGT").displayName) // case-insensitive
	}

	@Test
	fun displayName_leavesOtherNamesUnchanged() {
		assertEquals("core", item("core").displayName)
		assertEquals("my.template.cgt".dropLast(4), item("my.template.cgt").displayName)
		assertEquals("readme.txt", item("readme.txt").displayName)
	}

	@Test
	fun primaryTemplate_isFirst_orEmptyFallback() {
		val a = TemplateMetadata("A", "da", "1.0")
		val b = TemplateMetadata("B", "db", "2.0")
		assertEquals(a, item("x.cgt", listOf(a, b)).primaryTemplate)

		val empty = item("x.cgt", emptyList()).primaryTemplate
		assertEquals("", empty.name)
		assertEquals("", empty.version)
	}

	@Test
	fun hasMultipleTemplates_reflectsCount() {
		assertFalse(item("x.cgt", listOf(TemplateMetadata("A", "", "1"))).hasMultipleTemplates)
		assertTrue(
			item("x.cgt", listOf(TemplateMetadata("A", "", "1"), TemplateMetadata("B", "", "1")))
				.hasMultipleTemplates,
		)
		assertFalse(item("x.cgt", emptyList()).hasMultipleTemplates)
	}

	@Test
	fun versionLabel_prefixesWithV() {
		assertEquals("v1.0", versionLabel("1.0"))
		assertEquals("v0.1", versionLabel("0.1"))
		assertEquals("v1.2.3", versionLabel("1.2.3"))
	}

	@Test
	fun versionLabel_truncatesMoreThanThreeSegments() {
		// Only the first three dot-separated segments are kept (matches the host Plugin Manager).
		assertEquals("v1.0.0-build...", versionLabel("1.0.0-build.20260101"))
		assertEquals("v1.2.3...", versionLabel("1.2.3.4"))
	}

	@Test
	fun versionLabel_blankBecomesEmpty() {
		assertEquals("", versionLabel(""))
		assertEquals("", versionLabel("   "))
	}
}
