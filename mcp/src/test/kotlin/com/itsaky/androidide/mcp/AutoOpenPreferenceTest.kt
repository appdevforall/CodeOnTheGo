package com.itsaky.androidide.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val KEY = "idepref_general_autoOpenProjects"

private fun occurrencesOfKey(xml: String) = Regex(KEY).findAll(xml).count()

class AutoOpenPreferenceTest {
	@Test
	fun `creates a preferences document when none exists`() {
		val xml = withAutoOpenDisabled("")

		assertTrue(xml.contains("<map>"), xml)
		assertTrue(xml.contains("</map>"), xml)
		assertTrue(xml.contains("""<boolean name="$KEY" value="false" />"""), xml)
	}

	// Regression: the first version of this edit was line-based sed on-device.
	// A prior run left <map> and the boolean sharing one line, so deleting the
	// line took <map> with it and left the file corrupt on the second run.
	@Test
	fun `is idempotent and keeps the map element intact`() {
		val once = withAutoOpenDisabled("")
		val twice = withAutoOpenDisabled(once)

		assertEquals(once, twice)
		assertEquals(1, occurrencesOfKey(twice), twice)
		assertTrue(twice.contains("<map>"), twice)
	}

	@Test
	fun `preserves unrelated preferences and overwrites an existing true value`() {
		val existing =
			"""
			<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
			<map>
				<string name="idpref_general_theme">dark</string>
				<boolean name="$KEY" value="true" />
			</map>
			""".trimIndent()

		val xml = withAutoOpenDisabled(existing)

		assertTrue(xml.contains("""<string name="idpref_general_theme">dark</string>"""), xml)
		assertTrue(xml.contains("""<boolean name="$KEY" value="false" />"""), xml)
		assertFalse(xml.contains("""value="true""""), xml)
		assertEquals(1, occurrencesOfKey(xml), xml)
	}

	@Test
	fun `handles a self-closing map element`() {
		val xml = withAutoOpenDisabled("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />")

		assertTrue(xml.contains("""<boolean name="$KEY" value="false" />"""), xml)
		assertFalse(xml.contains("<map />"), xml)
		assertEquals(1, occurrencesOfKey(xml), xml)
	}
}
