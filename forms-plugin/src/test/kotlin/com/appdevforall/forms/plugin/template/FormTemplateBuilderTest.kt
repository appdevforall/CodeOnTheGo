package com.appdevforall.forms.plugin.template

import com.appdevforall.forms.plugin.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the static-stub schema's shape because two unrelated surfaces depend
 * on it:
 *
 * 1. The generated app's runtime renderer reads `assets/form_schema.json`
 *    and renders one input per field — it expects the stub schema to be
 *    valid JSON with a non-empty `fields` array so the empty-form code path
 *    never fires for unmodified projects.
 *
 * 2. [com.appdevforall.forms.plugin.panel.SchemaPanelFragment] uses the
 *    sentinel field id `f_placeholder` to detect the "untouched stub" case
 *    and surface a friendlier hint than "1 field configured".
 *
 * If you change either piece of behavior, update this test and the
 * downstream consumers together.
 */
class FormTemplateBuilderTest {

    @Test
    fun blankStubSchemaHasOnePlaceholderField() {
        val schema = FormTemplateBuilder.blankStubSchema()
        assertEquals(1, schema.fields.size)
        val placeholder = schema.fields[0]
        assertEquals("f_placeholder", placeholder.id)
        assertEquals(FieldType.TEXT, placeholder.type)
        assertTrue("placeholder field needs a non-empty label",
            placeholder.label.isNotBlank())
    }

    @Test
    fun blankStubSchemaSerializesToValidJson() {
        val schema = FormTemplateBuilder.blankStubSchema()
        val json = schema.toJson()
        // Ensure it's parseable and that `appName` survives the trip — that's
        // what the generated app's MainActivity uses as its title.
        val parsed = com.appdevforall.forms.plugin.FormSchema.fromJson(json)
        assertEquals(schema, parsed)
    }

    @Test
    fun staticStubTemplateNameIsTheUserFacingTitle() {
        // The template card title in the New Project grid must match the
        // user-facing string. Pin it so a future "stub" suffix doesn't
        // sneak back in.
        assertEquals(
            "Form-filling app from photo",
            FormTemplateBuilder.STATIC_STUB_TEMPLATE_NAME,
        )
    }
}
