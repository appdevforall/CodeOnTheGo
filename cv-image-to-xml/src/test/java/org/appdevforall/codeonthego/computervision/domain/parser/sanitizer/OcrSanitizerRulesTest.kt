package org.appdevforall.codeonthego.computervision.domain.parser.sanitizer

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrSanitizerRulesTest {

    @Test
    fun `color sanitizer fixes merged background color tokens`() {
        val sanitizer = ColorSanitizer()

        assertEquals("background red", sanitizer.sanitize("backgroundired"))
        assertEquals("background red", sanitizer.sanitize("backgroundred"))
    }

	@Test
	fun `text attribute sanitizer normalizes OCR variants of text style`() {
		val sanitizer = TextAttributeSanitizer()

		assertEquals("text_style", sanitizer.sanitize("text style"))
		assertEquals("text_style", sanitizer.sanitize("text stjle"))
	}

    @Test
	fun `dimension sanitizer fixes width and height OCR mistakes`() {
		val sanitizer = DimensionSanitizer()

		assertEquals("layout_width: 120dp", sanitizer.sanitize("iayout widh. 120dp"))
		assertEquals("layout_height: 48dp", sanitizer.sanitize("layout heist. 48dp"))
	}

    @Test
	fun `dimension sanitizer normalizes match parent OCR variants`() {
		val sanitizer = DimensionSanitizer()

		assertEquals("layout_width: match_parent", sanitizer.sanitize("layout_width: match parent"))
		assertEquals("layout_width: match_parent", sanitizer.sanitize("layout_width: match-parrent"))
	}

    @Test
    fun `margin and padding sanitizer preserves edge names`() {
        val sanitizer = MarginPaddingSanitizer()

        assertEquals("layout_margin_top: 16dp", sanitizer.sanitize("layout_margin top: 16dp"))
        assertEquals("padding_end: 8dp", sanitizer.sanitize("padding end: 8dp"))
    }

    @Test
    fun `structure sanitizer rewrites horizontal center layout phrase`() {
        val sanitizer = StructureSanitizer()

        assertEquals(
            "layout_gravity: center_horizontal",
            sanitizer.sanitize("horizontal gravity: center layout")
        )
    }

    @Test
	fun `default sanitizer applies multiple OCR cleanup rules in sequence`() {
		val sanitizer = OcrSanitizerFactory.createDefaultSanitizer()
		val input = "backgroundired | text stjle: bold | iayout widh. match parrent | padding end: 8dp"

        assertEquals(
            "background red | text_style: bold | layout_width: match_parent | padding_end: 8dp",
            sanitizer.sanitize(input)
        )
    }
}
