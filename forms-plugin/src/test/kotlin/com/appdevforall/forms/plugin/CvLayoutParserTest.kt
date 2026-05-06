package com.appdevforall.forms.plugin

import com.appdevforall.forms.plugin.wizard.CvLayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CvLayoutParser]. Pure-Kotlin / pure-JVM tests — no Android
 * runtime needed since the parser dropped its `android.util.Xml` dependency
 * in favour of `org.xmlpull.v1.XmlPullParserFactory`.
 *
 * The XML inputs mirror what `cv-image-to-xml/.../domain/xml/AndroidWidget.kt`
 * emits today — printed in the format `<Tag\n  android:id=\"...\"\n />`. We
 * pin the mapping table from the parser's KDoc so future changes to the CV
 * output (extra tags, renamed inputType values) are caught by this suite.
 */
class CvLayoutParserTest {

    @Test
    fun emptyXmlReturnsEmpty() {
        assertEquals(emptyList<FormField>(), CvLayoutParser.parse(""))
    }

    @Test
    fun garbageXmlReturnsEmpty() {
        // The parser swallows exceptions and returns empty rather than crashing
        // — the wizard treats that as "fall back to manual entry".
        assertEquals(emptyList<FormField>(), CvLayoutParser.parse("<not really xml"))
    }

    @Test
    fun editTextWithNumberInputTypeBecomesNumberField() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <EditText
                    android:id="@+id/age"
                    android:hint="Age"
                    android:inputType="number"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(1, fields.size)
        assertEquals("Age", fields[0].label)
        assertEquals(FieldType.NUMBER, fields[0].type)
    }

    @Test
    fun editTextWithDateInputTypeBecomesDateField() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <EditText android:hint="Date of birth" android:inputType="date"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(1, fields.size)
        assertEquals(FieldType.DATE, fields[0].type)
    }

    @Test
    fun editTextWithMultiLineFlagBecomesLongText() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <EditText android:hint="Notes" android:inputType="textMultiLine"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(FieldType.LONGTEXT, fields[0].type)
    }

    @Test
    fun editTextDefaultsToText() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <EditText android:hint="Name" android:inputType="text"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals("Name", fields[0].label)
        assertEquals(FieldType.TEXT, fields[0].type)
    }

    @Test
    fun checkBoxBecomesCheckboxField() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <CheckBox android:text="Pregnant?"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(1, fields.size)
        assertEquals("Pregnant?", fields[0].label)
        assertEquals(FieldType.CHECKBOX, fields[0].type)
    }

    @Test
    fun switchAlsoBecomesCheckboxField() {
        // Visually a Switch is just a styled CheckBox; the form runtime
        // treats them identically (boolean toggle) so we collapse the type.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <Switch android:text="Consent given"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(1, fields.size)
        assertEquals(FieldType.CHECKBOX, fields[0].type)
    }

    @Test
    fun unsupportedTagsAreSkipped() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:text="Section header"/>
                <Button android:text="Submit"/>
                <ImageView/>
                <Spinner/>
                <RadioButton android:text="Option A"/>
                <EditText android:hint="Email"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(1, fields.size)
        assertEquals("Email", fields[0].label)
    }

    @Test
    fun cvDefaultPlaceholdersAreSkipped() {
        // The CV pipeline emits "Enter text..." as a default hint when no
        // OCR text is detected. Surfacing those as fields would show the
        // user "(unnamed)" rows; better to drop and let them re-enter.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <EditText android:hint="Enter text..." android:inputType="text"/>
                <CheckBox android:text="CheckBox"/>
                <EditText android:hint="Real label" android:inputType="text"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(1, fields.size)
        assertEquals("Real label", fields[0].label)
    }

    @Test
    fun textInputEditTextIsRecognized() {
        // The CV generator can emit Material's TextInputEditText for some
        // detections. Make sure we recognise it under its fully-qualified name.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <com.google.android.material.textfield.TextInputEditText
                    android:hint="Phone"
                    android:inputType="phone"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(1, fields.size)
        assertEquals("Phone", fields[0].label)
    }

    @Test
    fun fieldsPreserveTopToBottomOrder() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <EditText android:hint="First"/>
                <EditText android:hint="Second"/>
                <CheckBox android:text="Third"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(listOf("First", "Second", "Third"), fields.map { it.label })
    }

    @Test
    fun parsedFieldsHaveStableIds() {
        // Mostly a smoke test that `WizardViewModel.newField` returns
        // unique IDs — adapter DiffUtil relies on this.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <EditText android:hint="A"/>
                <EditText android:hint="B"/>
            </LinearLayout>
        """.trimIndent()
        val fields = CvLayoutParser.parse(xml)
        assertEquals(2, fields.size)
        assertTrue(fields[0].id != fields[1].id)
        assertTrue(fields[0].id.startsWith("f_"))
    }
}
