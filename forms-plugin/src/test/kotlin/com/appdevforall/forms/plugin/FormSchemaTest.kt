package com.appdevforall.forms.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + structural tests for [FormSchema.toJson]. The JSON shape is
 * the contract between the wizard (write-side) and the generated form-data
 * app's runtime renderer (read-side), so any future change here is a
 * load-bearing change for both modules.
 */
class FormSchemaTest {

    @Test
    fun emptySchemaSerializesCleanly() {
        val schema = FormSchema(
            appName = "FormApp",
            packageName = "com.example.formapp",
            fields = emptyList(),
            submit = SubmitConfig(),
        )
        val obj = JSONObject(schema.toJson())
        assertEquals("FormApp", obj.getString("appName"))
        assertEquals("com.example.formapp", obj.getString("packageName"))
        assertEquals(0, obj.getJSONArray("fields").length())

        val sj = obj.getJSONObject("submit")
        // Default submit config: no postUrl, postAsJson true, no shares,
        // offline queue on.
        assertFalse(sj.has("postUrl"))
        assertTrue(sj.getBoolean("postAsJson"))
        assertFalse(sj.getBoolean("allowCsvShare"))
        assertFalse(sj.getBoolean("allowJsonShare"))
        assertTrue(sj.getBoolean("offlineQueue"))
    }

    @Test
    fun fullSchemaSerializesEveryField() {
        val schema = FormSchema(
            appName = "Vaccination intake",
            packageName = "org.ngo.intake",
            fields = listOf(
                FormField(id = "f_1", label = "Name", type = FieldType.TEXT, required = true),
                FormField(id = "f_2", label = "DOB", type = FieldType.DATE, required = true),
                FormField(id = "f_3", label = "Notes", type = FieldType.LONGTEXT),
                FormField(id = "f_4", label = "Pregnant?", type = FieldType.CHECKBOX),
                FormField(id = "f_5", label = "Postal", type = FieldType.TEXT, reusable = true, confidence = 0.91),
            ),
            submit = SubmitConfig(
                postUrl = "https://example.org/intake",
                postAsJson = true,
                allowCsvShare = true,
                allowJsonShare = false,
                offlineQueue = true,
            ),
        )

        val obj = JSONObject(schema.toJson())
        val fields = obj.getJSONArray("fields")
        assertEquals(5, fields.length())

        val f1 = fields.getJSONObject(0)
        assertEquals("Name", f1.getString("label"))
        assertEquals("text", f1.getString("type"))
        assertTrue(f1.getBoolean("required"))
        assertFalse(f1.has("confidence"))

        val f5 = fields.getJSONObject(4)
        assertTrue(f5.getBoolean("reusable"))
        assertEquals(0.91, f5.getDouble("confidence"), 0.0001)

        val sj = obj.getJSONObject("submit")
        assertEquals("https://example.org/intake", sj.getString("postUrl"))
        assertTrue(sj.getBoolean("allowCsvShare"))
        assertFalse(sj.getBoolean("allowJsonShare"))
    }

    @Test
    fun fieldTypeIdsAreStable() {
        // The generated app reads these ids from form_schema.json verbatim;
        // changing them silently breaks every previously-emitted project.
        // This test pins the public contract so renaming requires a code
        // search of all consumers.
        assertEquals("text", FieldType.TEXT.id)
        assertEquals("longtext", FieldType.LONGTEXT.id)
        assertEquals("number", FieldType.NUMBER.id)
        assertEquals("date", FieldType.DATE.id)
        assertEquals("checkbox", FieldType.CHECKBOX.id)
    }

    @Test
    fun fieldTypeFromIdRoundTrips() {
        for (t in FieldType.values()) {
            assertEquals(t, FieldType.fromId(t.id))
        }
    }

    @Test
    fun fieldTypeFromIdFallsBackToText() {
        assertEquals(FieldType.TEXT, FieldType.fromId("unknown"))
        assertEquals(FieldType.TEXT, FieldType.fromId(""))
    }

    @Test
    fun fromJsonRoundTripsFullSchema() {
        val original = FormSchema(
            appName = "Vaccination intake",
            packageName = "org.ngo.intake",
            fields = listOf(
                FormField(id = "f_1", label = "Name", type = FieldType.TEXT, required = true),
                FormField(id = "f_2", label = "DOB", type = FieldType.DATE, required = true),
                FormField(id = "f_3", label = "Notes", type = FieldType.LONGTEXT),
                FormField(id = "f_4", label = "Pregnant?", type = FieldType.CHECKBOX),
                FormField(id = "f_5", label = "Postal", type = FieldType.TEXT, reusable = true, confidence = 0.91),
            ),
            submit = SubmitConfig(
                postUrl = "https://example.org/intake",
                postAsJson = true,
                allowCsvShare = true,
                allowJsonShare = false,
                offlineQueue = true,
            ),
        )
        val parsed = FormSchema.fromJson(original.toJson())!!
        assertEquals(original, parsed)
    }

    @Test
    fun fromJsonHandlesEmptySchema() {
        val original = FormSchema(
            appName = "Stub",
            packageName = "com.example.stub",
            fields = emptyList(),
            submit = SubmitConfig(),
        )
        val parsed = FormSchema.fromJson(original.toJson())!!
        assertEquals(original, parsed)
    }

    @Test
    fun fromJsonReturnsNullForGarbage() {
        assertEquals(null, FormSchema.fromJson("not json {{"))
    }

    @Test
    fun fromJsonToleratesMissingFields() {
        // Hand-edited or partial schemas should still parse rather than blow
        // up the panel's renderer.
        val parsed = FormSchema.fromJson("""{"appName":"X"}""")!!
        assertEquals("X", parsed.appName)
        assertEquals("", parsed.packageName)
        assertEquals(0, parsed.fields.size)
        assertTrue(parsed.submit.postAsJson)
        assertTrue(parsed.submit.offlineQueue)
        assertFalse(parsed.submit.allowCsvShare)
    }

    @Test
    fun fromJsonSkipsFieldsWithoutId() {
        val json = """
            {
              "appName":"X",
              "packageName":"com.x",
              "fields":[
                {"id":"ok","label":"OK","type":"text"},
                {"label":"missing id","type":"text"},
                {"id":"","label":"empty id","type":"text"}
              ],
              "submit":{}
            }
        """.trimIndent()
        val parsed = FormSchema.fromJson(json)!!
        assertEquals(1, parsed.fields.size)
        assertEquals("ok", parsed.fields[0].id)
    }
}
