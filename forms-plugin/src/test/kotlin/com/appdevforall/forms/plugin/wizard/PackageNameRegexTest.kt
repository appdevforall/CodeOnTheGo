package com.appdevforall.forms.plugin.wizard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin behavior of [WizardHostFragment.PACKAGE_NAME_REGEX]. Step 1 of the
 * wizard validates the user's package-name input against this regex; a
 * mismatch surfaces as a toast at Continue time. The shape is intentionally
 * stricter than `Manifest.applicationId` allows (which permits uppercase)
 * so user input that wouldn't compile cleanly gets caught before it lands
 * in a generated project's Gradle build.
 *
 * If this regex is loosened or tightened, expect to update these tests in
 * the same commit.
 */
class PackageNameRegexTest {

    private val regex = WizardHostFragment.PACKAGE_NAME_REGEX

    @Test
    fun acceptsCanonicalLowercaseDotSeparatedNames() {
        assertTrue("com.example", regex.matches("com.example"))
        assertTrue("org.ngo.intake", regex.matches("org.ngo.intake"))
        assertTrue("com.example.formapp", regex.matches("com.example.formapp"))
        assertTrue("a.b", regex.matches("a.b"))
    }

    @Test
    fun acceptsDigitsAndUnderscoresAfterFirstChar() {
        assertTrue("com.example2", regex.matches("com.example2"))
        assertTrue("com.example_app", regex.matches("com.example_app"))
        assertTrue("com.foo_bar.baz_qux", regex.matches("com.foo_bar.baz_qux"))
    }

    @Test
    fun rejectsSingleSegment() {
        // Java package convention requires at least two segments. A bare
        // word would lead to `applicationId = "formapp"` which Gradle still
        // accepts but is the canonical "user typo" we want to catch.
        assertFalse("formapp", regex.matches("formapp"))
        assertFalse("FormApp", regex.matches("FormApp"))
    }

    @Test
    fun rejectsUppercaseSegments() {
        // Stricter than Android allows, intentionally: Java tooling
        // conventionally lowercases package names, and IDE displays render
        // uppercase package names oddly. Catch it here, suggest lowercase.
        assertFalse("Com.Example", regex.matches("Com.Example"))
        assertFalse("com.Example", regex.matches("com.Example"))
    }

    @Test
    fun rejectsSegmentsStartingWithDigitOrUnderscore() {
        assertFalse("1com.example", regex.matches("1com.example"))
        assertFalse("com.2example", regex.matches("com.2example"))
        assertFalse("_com.example", regex.matches("_com.example"))
        assertFalse("com._example", regex.matches("com._example"))
    }

    @Test
    fun rejectsHyphensAndOtherSeparators() {
        // `com.foo-bar` is a common typo (npm-style hyphen). Catch it.
        assertFalse("com.foo-bar", regex.matches("com.foo-bar"))
        assertFalse("com foo bar", regex.matches("com foo bar"))
        assertFalse("com/foo/bar", regex.matches("com/foo/bar"))
    }

    @Test
    fun rejectsEmptyOrLeadingTrailingDot() {
        assertFalse("(empty)", regex.matches(""))
        assertFalse(".com.example", regex.matches(".com.example"))
        assertFalse("com.example.", regex.matches("com.example."))
        assertFalse("com..example", regex.matches("com..example"))
    }
}
