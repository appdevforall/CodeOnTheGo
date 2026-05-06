package com.appdevforall.forms.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * In-process model for the form schema captured by the wizard. Persisted into
 * the generated app as `assets/form_schema.json` so the renderer can dynamically
 * lay out fields at runtime.
 *
 * The schema is intentionally narrow for R2 — text / longtext / number / date /
 * checkbox. Headers and groups in the design doc (FormElement discriminated
 * union) are deferred to R3 once the YOLO model emits those classes
 * (see plan §10 Q13).
 */
enum class FieldType(val id: String, val label: String) {
    TEXT("text", "Short text"),
    LONGTEXT("longtext", "Long text"),
    NUMBER("number", "Number"),
    DATE("date", "Date"),
    CHECKBOX("checkbox", "Checkbox");

    companion object {
        fun fromId(id: String): FieldType = values().firstOrNull { it.id == id } ?: TEXT
    }
}

data class FormField(
    val id: String,
    val label: String,
    val type: FieldType,
    val required: Boolean = false,
    val reusable: Boolean = false,
    /** Optional confidence for CV-detected fields (0..1). null for manually added. */
    val confidence: Double? = null,
)

data class SubmitConfig(
    val postUrl: String? = null,
    val postAsJson: Boolean = true,
    val allowCsvShare: Boolean = false,
    val allowJsonShare: Boolean = false,
    val offlineQueue: Boolean = true,
)

data class FormSchema(
    val appName: String,
    val packageName: String,
    val fields: List<FormField>,
    val submit: SubmitConfig,
) {
    fun toJson(): String = buildJson(this).toString(2)

    companion object {
        private fun buildJson(s: FormSchema): JSONObject {
            val root = JSONObject()
            root.put("appName", s.appName)
            root.put("packageName", s.packageName)
            val fieldsJson = JSONArray()
            for (f in s.fields) {
                val fj = JSONObject()
                fj.put("id", f.id)
                fj.put("label", f.label)
                fj.put("type", f.type.id)
                fj.put("required", f.required)
                fj.put("reusable", f.reusable)
                if (f.confidence != null) fj.put("confidence", f.confidence)
                fieldsJson.put(fj)
            }
            root.put("fields", fieldsJson)
            val sj = JSONObject()
            s.submit.postUrl?.let { sj.put("postUrl", it) }
            sj.put("postAsJson", s.submit.postAsJson)
            sj.put("allowCsvShare", s.submit.allowCsvShare)
            sj.put("allowJsonShare", s.submit.allowJsonShare)
            sj.put("offlineQueue", s.submit.offlineQueue)
            root.put("submit", sj)
            return root
        }
    }
}
