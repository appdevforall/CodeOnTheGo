package com.appdevforall.forms.plugin.wizard

import com.appdevforall.forms.plugin.FieldType
import com.appdevforall.forms.plugin.FormField
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parses the Android layout XML returned by `ComputerVisionActivity` into a
 * list of [FormField] suggestions. The CV pipeline emits a `<LinearLayout>`
 * tree containing `EditText`, `CheckBox`, `RadioButton`, `Switch`,
 * `TextView`, `Spinner`, etc. (see
 * `cv-image-to-xml/.../domain/xml/AndroidWidget.kt` for the full mapping
 * from YOLO classes to widget tags).
 *
 * For R2 we only surface fields the form-data app can actually render:
 *
 * | XML tag    | Mapped FieldType | Notes |
 * |------------|------------------|-------|
 * | EditText (inputType=number / numberDecimal / numberSigned) | NUMBER | |
 * | EditText (inputType=date / time / datetime) | DATE | |
 * | EditText (inputType=textMultiLine / lines>1)  | LONGTEXT | |
 * | EditText (anything else)  | TEXT | including the default hint-only inputs |
 * | CheckBox / Switch  | CHECKBOX | |
 *
 * Skipped (R3 candidates):
 * - `RadioButton` / `RadioGroup` — discriminated union not yet in [FormField]
 * - `Spinner` — needs entries / dropdown UI (open question 10.13)
 * - `Button`, `ImageView`, `ImageButton` — not data-collection fields
 * - `TextView` — labels are already captured by the YOLO pipeline as
 *   `android:hint` / `android:text` on neighbouring widgets, so we don't
 *   need to surface them as standalone form fields
 *
 * **OCR alternation handling.** The CV pipeline already runs hint/text
 * cleanup; we additionally trim hints / labels and skip empty ones to avoid
 * surfacing junk fields. The codeonthego-review-checklist §8 calls out
 * known OCR alternations (B/8, O/0, unit-suffix corruption); we leave those
 * for the user to fix in step 2 since the wizard is the correction surface.
 */
internal object CvLayoutParser {

    /**
     * Parse the given Android layout XML string. Returns a list of
     * [FormField] in the order they appear (top-to-bottom). Returns an
     * empty list if the XML is unparseable rather than throwing — the
     * wizard treats that as "CV produced no usable fields, fall back to
     * manual entry".
     */
    fun parse(layoutXml: String): List<FormField> {
        if (layoutXml.isBlank()) return emptyList()
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(layoutXml))
            walk(parser)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun walk(parser: XmlPullParser): List<FormField> {
        val out = mutableListOf<FormField>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val field = mapStartTag(parser)
                if (field != null) out.add(field)
            }
            event = parser.next()
        }
        return out
    }

    private fun mapStartTag(parser: XmlPullParser): FormField? {
        val tag = parser.name ?: return null
        val attrs = readAttrs(parser)
        return when (tag) {
            "EditText",
            "androidx.appcompat.widget.AppCompatEditText",
            "com.google.android.material.textfield.TextInputEditText" -> editText(attrs)

            "CheckBox",
            "androidx.appcompat.widget.AppCompatCheckBox",
            "Switch",
            "SwitchCompat",
            "androidx.appcompat.widget.SwitchCompat",
            "com.google.android.material.switchmaterial.SwitchMaterial" -> checkboxOrSwitch(attrs)

            else -> null
        }
    }

    private fun editText(attrs: Map<String, String>): FormField? {
        val label = chooseLabel(attrs) ?: return null
        val inputType = (attrs["inputType"] ?: attrs["android:inputType"]).orEmpty().lowercase()
        val isMultiLine = "textmultiline" in inputType ||
            (attrs["lines"]?.toIntOrNull() ?: 1) > 1 ||
            (attrs["maxLines"]?.toIntOrNull() ?: 1) > 1

        val type = when {
            "number" in inputType -> FieldType.NUMBER
            "date" in inputType || "time" in inputType -> FieldType.DATE
            isMultiLine -> FieldType.LONGTEXT
            else -> FieldType.TEXT
        }
        return WizardViewModel.newField(label = label, type = type)
    }

    private fun checkboxOrSwitch(attrs: Map<String, String>): FormField? {
        val label = chooseLabel(attrs) ?: return null
        return WizardViewModel.newField(label = label, type = FieldType.CHECKBOX)
    }

    /**
     * Pick the most user-readable label for a widget. Prefers hint, falls
     * back to text, then contentDescription. Returns null if no label is
     * available — we'd rather drop the widget than surface a field labelled
     * "(unnamed)" when the user has no idea what it represents.
     */
    private fun chooseLabel(attrs: Map<String, String>): String? {
        val candidates = listOf(
            attrs["hint"], attrs["android:hint"],
            attrs["text"], attrs["android:text"],
            attrs["contentDescription"], attrs["android:contentDescription"],
        )
        for (c in candidates) {
            val cleaned = c?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            // Skip placeholder strings that the CV pipeline emits as defaults.
            if (cleaned.equals("Enter text...", ignoreCase = true)) continue
            if (cleaned.equals("CheckBox", ignoreCase = true)) continue
            if (cleaned.equals("Switch", ignoreCase = true)) continue
            return cleaned
        }
        return null
    }

    private fun readAttrs(parser: XmlPullParser): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val n = parser.attributeCount
        for (i in 0 until n) {
            val rawName = parser.getAttributeName(i) ?: continue
            val value = parser.getAttributeValue(i) ?: continue
            // Store both the prefixless name and the namespaced "android:foo"
            // form so we can look up either.
            out[rawName] = value
            val prefix = parser.getAttributePrefix(i)
            if (prefix != null) out["$prefix:$rawName"] = value
        }
        return out
    }
}
