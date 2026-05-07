package com.appdevforall.forms.plugin.ocr

import com.appdevforall.forms.plugin.FieldType
import com.appdevforall.forms.plugin.FormField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HeuristicFieldClassifier].
 *
 * Fixtures mirror what NGO field intake forms typically look like — the
 * audience the Forms plugin is targeting. We don't test the rendering side
 * (that's [com.appdevforall.forms.plugin.template.FormTemplateBuilderTest])
 * or the wizard flow (that's the Kaspresso suite). These tests pin the
 * "OCR-shaped input -> FormField list" contract.
 */
class HeuristicFieldClassifierTest {

    private val classifier = HeuristicFieldClassifier()

    // -- canonical fixture: vaccination intake --------------------------

    @Test
    fun vaccinationIntakeFormProducesExpectedFields() {
        val ocr = ocr {
            block(0, 0) {
                line("VACCINATION CAMP — INTAKE FORM", style = Style.LARGE_BOLD)
            }
            block(0, 60) {
                line("Name: ___________________________")
            }
            block(0, 90) {
                line("DOB: __ / __ / ____")
            }
            block(0, 120) {
                line("Postal code: _______")
            }
            block(0, 150) {
                line("☐ Pregnant?")
            }
            block(0, 180) {
                line("☐ Consent to data collection")
            }
        }
        val fields = classifier.classify(ocr)
        assertEquals(
            listOf(
                "Name" to FieldType.TEXT,
                "DOB" to FieldType.DATE,
                "Postal code" to FieldType.TEXT,
                "Pregnant?" to FieldType.CHECKBOX,
                "Consent to data collection" to FieldType.CHECKBOX,
            ),
            fields.map { it.label to it.type },
        )
        // Every auto-detected field should carry a non-null confidence so
        // the wizard can style them differently from manually added ones.
        assertTrue(fields.all { it.confidence != null })
    }

    // -- voter registration with signature + radio ---------------------

    @Test
    fun voterRegistrationFormProducesExpectedFields() {
        val ocr = ocr {
            block(0, 0) {
                line("VOTER REGISTRATION", style = Style.LARGE_BOLD)
            }
            block(0, 50) { line("Name: _________________") }
            block(0, 80) { line("Address: _________________") }
            block(0, 110) { line("Date of birth: __/__/____") }
            block(0, 140) { line("○ Citizen   ○ Non-citizen") }
            block(0, 170) { line("Signature: _________________") }
        }
        val fields = classifier.classify(ocr)
        // We expect Name + Address + DOB + (radio-line yields one field) +
        // Signature surfaced as TEXT.
        val labels = fields.map { it.label }
        assertTrue("missing Name in $labels", labels.contains("Name"))
        assertTrue("missing Address in $labels", labels.contains("Address"))
        assertTrue("missing Date of birth in $labels", labels.contains("Date of birth"))
        assertTrue("missing Signature in $labels", labels.contains("Signature"))

        val dob = fields.first { it.label == "Date of birth" }
        assertEquals(FieldType.DATE, dob.type)

        val signature = fields.first { it.label == "Signature" }
        // Signature surfaces as TEXT — the schema doesn't have a signature
        // role yet (gap doc tracks this).
        assertEquals(FieldType.TEXT, signature.type)
    }

    // -- date-keyword detection --------------------------------------

    @Test
    fun dateKeywordWithoutMaskStillDetectedAsDate() {
        // "Date of birth:" with trailing underscore (no slash mask) — the
        // keyword-match path should kick in.
        val ocr = ocr {
            block(0, 0) {
                line("Date of birth: ___________")
            }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals(FieldType.DATE, fields[0].type)
        assertEquals("Date of birth", fields[0].label)
    }

    @Test
    fun expiryDateKeywordDetectedAsDate() {
        val ocr = ocr {
            block(0, 0) { line("Expiry: __________") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(FieldType.DATE, fields[0].type)
    }

    // -- number keyword detection -----------------------------------

    @Test
    fun numberKeywordDetectedAsNumber() {
        val ocr = ocr {
            block(0, 0) { line("Age: ____") }
            block(0, 30) { line("Phone: _____________") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(2, fields.size)
        assertEquals(FieldType.NUMBER, fields[0].type)
        assertEquals(FieldType.NUMBER, fields[1].type)
    }

    // -- long-text detection ----------------------------------------

    @Test
    fun longTextKeywordDetectedAsLongText() {
        val ocr = ocr {
            block(0, 0) { line("Comments: _________________") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(FieldType.LONGTEXT, fields[0].type)
    }

    @Test
    fun multiLineRuledAreaDetectedAsLongText() {
        // "Notes:" on its own line, followed by 3 underscore-only lines
        // -> LONGTEXT. Tests the ruled-area detection path.
        val ocr = ocr {
            block(0, 0) {
                line("Notes:")
                line("___________________________")
                line("___________________________")
                line("___________________________")
            }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("Notes", fields[0].label)
        assertEquals(FieldType.LONGTEXT, fields[0].type)
    }

    // -- required-marker detection ---------------------------------

    @Test
    fun requiredAsteriskMarksFieldRequired() {
        val ocr = ocr {
            block(0, 0) { line("Name *: _________________") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("Name", fields[0].label)
        assertTrue("expected required=true", fields[0].required)
    }

    @Test
    fun requiredParenSuffixMarksFieldRequired() {
        val ocr = ocr {
            block(0, 0) { line("Email (required): _________________") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("Email", fields[0].label)
        assertTrue(fields[0].required)
    }

    @Test
    fun missingMarkerLeavesFieldOptional() {
        val ocr = ocr {
            block(0, 0) { line("Name: _________________") }
        }
        val fields = classifier.classify(ocr)
        assertFalse(fields[0].required)
    }

    // -- patterns the classifier should NOT trigger on -------------

    @Test
    fun blankFormProducesNoFields() {
        val ocr = ocr {}
        assertEquals(emptyList<FormField>(), classifier.classify(ocr))
    }

    @Test
    fun pureProseProducesNoFields() {
        // A paragraph block with no underscores, no glyphs, no colons-with-mask
        val ocr = ocr {
            block(0, 0) {
                line("Please complete this form to the best of your ability.")
                line("Submit it to the registration desk when finished.")
            }
        }
        val fields = classifier.classify(ocr)
        assertEquals(emptyList<FormField>(), fields)
    }

    @Test
    fun allCapsHeaderIsNotEmittedAsField() {
        val ocr = ocr {
            block(0, 0) { line("VACCINATION INTAKE", style = Style.LARGE_BOLD) }
            block(0, 50) { line("body 1", style = Style.NORMAL) }
            block(0, 80) { line("body 2", style = Style.NORMAL) }
            block(0, 110) { line("body 3", style = Style.NORMAL) }
        }
        val fields = classifier.classify(ocr)
        assertEquals(emptyList<FormField>(), fields)
    }

    @Test
    fun groupHeaderIsNotEmittedAsField() {
        // "Personal Information" is in the group-headers locale set; we
        // suppress it rather than surfacing as a TEXT field.
        val ocr = ocr {
            block(0, 0) { line("Personal Information") }
        }
        assertEquals(emptyList<FormField>(), classifier.classify(ocr))
    }

    @Test
    fun colonWithProseValueIsNotEmittedAsField() {
        // "Section: Personal Information" is a heading-style line, not a
        // form field. Should not produce a TEXT field.
        val ocr = ocr {
            block(0, 0) { line("Section: Personal Information") }
        }
        val fields = classifier.classify(ocr)
        // There should be no field — the line is a heading.
        assertTrue(fields.isEmpty())
    }

    // -- form-with-only-checkboxes edge case --------------------------

    @Test
    fun formWithOnlyCheckboxesYieldsOnlyCheckboxes() {
        val ocr = ocr {
            block(0, 0) { line("☐ Option A") }
            block(0, 30) { line("☐ Option B") }
            block(0, 60) { line("☐ Option C") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(3, fields.size)
        assertTrue(fields.all { it.type == FieldType.CHECKBOX })
        assertEquals(listOf("Option A", "Option B", "Option C"), fields.map { it.label })
    }

    // -- malformed OCR (single huge block) ----------------------------

    @Test
    fun singleBlockWithMixedContentIsBestEffort() {
        // ML Kit can sometimes return one giant block when the form is
        // taken at an awkward angle. The classifier should still process
        // the lines inside and not crash.
        val ocr = ocr {
            block(0, 0) {
                line("INTAKE FORM", style = Style.LARGE_BOLD)
                line("Name: _________________")
                line("DOB: __/__/____")
                line("☐ Consent")
            }
        }
        val fields = classifier.classify(ocr)
        // Header dropped, three fields survive.
        assertEquals(3, fields.size)
        assertEquals(setOf("Name", "DOB", "Consent"), fields.map { it.label }.toSet())
    }

    @Test
    fun emptyTextLinesAreIgnored() {
        val ocr = ocr {
            block(0, 0) {
                line("Name: _________")
                line("   ")
                line("Phone: _________")
            }
        }
        val fields = classifier.classify(ocr)
        assertEquals(2, fields.size)
        assertEquals("Name", fields[0].label)
        assertEquals("Phone", fields[1].label)
    }

    @Test
    fun fieldsPreserveTopToBottomOrder() {
        val ocr = ocr {
            block(0, 0) { line("First: _____") }
            block(0, 30) { line("Second: _____") }
            block(0, 60) { line("Third: _____") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(listOf("First", "Second", "Third"), fields.map { it.label })
    }

    // -- locale override ----------------------------------------------

    @Test
    fun customLocaleAddsKeywordSupport() {
        // Spanish-ish locale: "fecha" -> DATE, "comentarios" -> LONGTEXT
        val es = ClassifierLocale(
            dateKeywords = setOf("fecha"),
            signatureKeywords = setOf("firma"),
            groupHeaders = setOf("información personal"),
            numberKeywords = setOf("edad"),
            longTextKeywords = setOf("comentarios"),
            requiredMarkers = setOf("*", "(obligatorio)"),
        )
        val classifier = HeuristicFieldClassifier(es)
        val ocr = ocr {
            block(0, 0) { line("Fecha: __________") }
            block(0, 30) { line("Edad: _____") }
            block(0, 60) { line("Comentarios: __________") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(3, fields.size)
        assertEquals(FieldType.DATE, fields[0].type)
        assertEquals(FieldType.NUMBER, fields[1].type)
        assertEquals(FieldType.LONGTEXT, fields[2].type)
    }

    // -- confidence + ID stability ------------------------------------

    @Test
    fun fieldsHaveStableIdsAndDefaultConfidence() {
        val ocr = ocr {
            block(0, 0) { line("A: ____") }
            block(0, 30) { line("B: ____") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(2, fields.size)
        assertNotNull(fields[0].confidence)
        assertNotNull(fields[1].confidence)
        // IDs should be unique and follow the wizard's "f_" prefix.
        assertTrue(fields[0].id != fields[1].id)
        assertTrue(fields.all { it.id.startsWith("f_") })
    }

    // -- numeric vs date disambiguation -------------------------------

    @Test
    fun ageBeatsDateWhenLabelIsAgeWithNumberInValue() {
        // "Age:" must map to NUMBER, not get confused by an OCR-style "12"
        // value being slash-shaped.
        val ocr = ocr {
            block(0, 0) { line("Age: __") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(FieldType.NUMBER, fields[0].type)
    }

    @Test
    fun dateMaskInValueDetectedEvenWithoutDateKeyword() {
        // No date keyword in the label — but the value is unambiguously a
        // date mask. We pick DATE.
        val ocr = ocr {
            block(0, 0) { line("Visit: __/__/____") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(FieldType.DATE, fields[0].type)
        assertEquals("Visit", fields[0].label)
    }

    // -- defensive: classifier never throws ---------------------------

    @Test
    fun classifierIsRobustToWeirdInput() {
        // Lines that are just punctuation, just digits, just whitespace.
        val ocr = ocr {
            block(0, 0) { line("---") }
            block(0, 30) { line("12345") }
            block(0, 60) { line("!@#$%") }
        }
        val fields = classifier.classify(ocr)
        // None of these should produce fields; classifier should not crash.
        assertEquals(emptyList<FormField>(), fields)
    }

    @Test
    fun classifierHandlesLabelWithNoColon() {
        // No colon, no mask, no glyph -> not a field.
        val ocr = ocr {
            block(0, 0) { line("Just some text on a line") }
        }
        assertEquals(emptyList<FormField>(), classifier.classify(ocr))
    }

    // -- word-boundary keyword matching (forms-benchmark regressions) --

    @Test
    fun villageInLabelDoesNotTriggerAgeKeyword() {
        // Forms-benchmark regression: "Town / village: ___" was classified
        // as NUMBER because "age" was matching as a substring of "village".
        // Word-boundary matching kills the false positive (31 cases).
        val ocr = ocr {
            block(0, 0) { line("Town / village: _______") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("Town / village", fields[0].label)
        assertEquals(FieldType.TEXT, fields[0].type)
    }

    @Test
    fun placeOfBirthIsNotClassifiedAsDate() {
        // Forms-benchmark regression: "Place of birth" was classified as
        // DATE because "birth" appeared as a date keyword. We removed
        // bare "birth" from the date keywords (4 cases) — "Date of birth"
        // still matches via "date".
        val ocr = ocr {
            block(0, 0) { line("Place of birth: _______") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("Place of birth", fields[0].label)
        assertEquals(FieldType.TEXT, fields[0].type)
    }

    @Test
    fun dateOfBirthStillClassifiedAsDate() {
        // Regression guard for the "birth" removal: "Date of birth"
        // continues to be a DATE because "date" matches.
        val ocr = ocr {
            block(0, 0) { line("Date of birth: _______") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals(FieldType.DATE, fields[0].type)
    }

    @Test
    fun telephoneNumberStillClassifiedAsNumber() {
        // Positive regression guard — word-boundary matching must still
        // accept legitimate full-token matches.
        val ocr = ocr {
            block(0, 0) { line("Telephone number: _____________") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(FieldType.NUMBER, fields[0].type)
    }

    @Test
    fun phoneNumberStillClassifiedAsNumber() {
        val ocr = ocr {
            block(0, 0) { line("Phone number: _____________") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(FieldType.NUMBER, fields[0].type)
    }

    // -- multi-checkbox-on-one-line (forms-benchmark regression) -------

    @Test
    fun multiCheckboxLineUsesPrefixAsLabel() {
        // Forms-benchmark regression: "Sex: ☐ M ☐ F" used to emit ONE
        // CHECKBOX with label "M ☐ F" (or similar), losing the "Sex"
        // prefix entirely. Now the prefix is preserved as the label.
        val ocr = ocr {
            block(0, 0) { line("Sex: ☐ M ☐ F") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("Sex", fields[0].label)
        assertEquals(FieldType.CHECKBOX, fields[0].type)
    }

    @Test
    fun multiCheckboxLineWithThreeOptionsUsesPrefix() {
        val ocr = ocr {
            block(0, 0) { line("Gender: ☐ Male ☐ Female ☐ Other") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("Gender", fields[0].label)
        assertEquals(FieldType.CHECKBOX, fields[0].type)
    }

    @Test
    fun singleCheckboxLineStillProducesOneField() {
        // Regression guard for the single-checkbox path — "☐ Pregnant?"
        // must continue to produce ONE CHECKBOX with label "Pregnant?".
        val ocr = ocr {
            block(0, 0) { line("☐ Pregnant?") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("Pregnant?", fields[0].label)
        assertEquals(FieldType.CHECKBOX, fields[0].type)
    }

    @Test
    fun multiCheckboxLineWithoutPrefixFallsBackToOptions() {
        // Edge case: "☐ A ☐ B" with no prefix label. Document-and-test
        // the chosen fallback: emit ONE CHECKBOX with the options joined
        // ("A / B") as the label. Better than dropping the line entirely.
        val ocr = ocr {
            block(0, 0) { line("☐ A ☐ B") }
        }
        val fields = classifier.classify(ocr)
        assertEquals(1, fields.size)
        assertEquals("A / B", fields[0].label)
        assertEquals(FieldType.CHECKBOX, fields[0].type)
    }

    @Test
    fun checkboxJoinedWithLabelInSameElementIsRecognized() {
        // ML Kit sometimes returns "☐Pregnant?" as a single element if the
        // glyph and label are touching. The fallback path handles that.
        val joinedLine = OcrLine(
            text = "☐ Pregnant?",
            bounds = Rect(0, 0, 200, 20),
            elements = listOf(
                OcrElement(text = "☐Pregnant?", bounds = Rect(0, 0, 200, 20)),
            ),
        )
        val ocr = OcrResult(
            blocks = listOf(
                OcrBlock(text = joinedLine.text, bounds = joinedLine.bounds, lines = listOf(joinedLine)),
            ),
        )
        val fields = classifier.classify(ocr)
        // The fallback uses line.text — so the joined element doesn't
        // matter, only the OcrLine.text. Either way we should detect.
        assertEquals(1, fields.size)
        assertEquals(FieldType.CHECKBOX, fields[0].type)
        assertEquals("Pregnant?", fields[0].label)
    }
}
