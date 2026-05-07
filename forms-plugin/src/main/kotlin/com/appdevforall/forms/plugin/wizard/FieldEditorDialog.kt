package com.appdevforall.forms.plugin.wizard

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.RadioGroup
import androidx.fragment.app.DialogFragment
import com.appdevforall.forms.plugin.FieldType
import com.appdevforall.forms.plugin.FormField
import com.appdevforall.forms.plugin.FormsPlugin
import com.appdevforall.forms.plugin.R
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

/**
 * Dialog for adding or editing a single [FormField]. The host fragment passes
 * the field via [newInstance] (or null for "add a new field") and receives the
 * result via [onFieldSaved].
 *
 * Kept small intentionally — semantic rules live on a dedicated step (step 3)
 * rather than being mashed into this dialog. Postal-code lookup arrives in C4.
 */
internal class FieldEditorDialog : DialogFragment() {

    var onFieldSaved: ((FormField) -> Unit)? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        return PluginFragmentHelper.getPluginInflater(
            FormsPlugin.PLUGIN_ID,
            super.onGetLayoutInflater(savedInstanceState),
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        // Use the framework-provided layoutInflater (which goes through our
        // onGetLayoutInflater override), not LayoutInflater.from(ctx) — the
        // latter resolves to the host activity's resources and won't find
        // R.layout.dialog_field_editor in the plugin APK.
        val view = layoutInflater.inflate(R.layout.dialog_field_editor, null)
        val labelEdit = view.findViewById<TextInputEditText>(R.id.forms_field_editor_label)
        val typeGroup = view.findViewById<RadioGroup>(R.id.forms_field_editor_type)

        val initial = arguments?.let {
            val id = it.getString(ARG_ID)
            if (id != null) {
                FormField(
                    id = id,
                    label = it.getString(ARG_LABEL).orEmpty(),
                    type = FieldType.fromId(it.getString(ARG_TYPE) ?: FieldType.TEXT.id),
                    required = it.getBoolean(ARG_REQUIRED, false),
                    reusable = it.getBoolean(ARG_REUSABLE, false),
                    confidence = if (it.containsKey(ARG_CONFIDENCE)) it.getDouble(ARG_CONFIDENCE) else null,
                )
            } else null
        }

        if (initial != null) {
            labelEdit.setText(initial.label)
            val typeRb = when (initial.type) {
                FieldType.TEXT -> R.id.forms_field_editor_type_text
                FieldType.LONGTEXT -> R.id.forms_field_editor_type_longtext
                FieldType.NUMBER -> R.id.forms_field_editor_type_number
                FieldType.DATE -> R.id.forms_field_editor_type_date
                FieldType.CHECKBOX -> R.id.forms_field_editor_type_checkbox
            }
            typeGroup.check(typeRb)
        }

        val title = if (initial == null) "Add field" else "Edit field"
        return AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.forms_wizard_field_save) { _, _ ->
                val labelText = labelEdit.text?.toString().orEmpty().trim()
                val type = when (typeGroup.checkedRadioButtonId) {
                    R.id.forms_field_editor_type_longtext -> FieldType.LONGTEXT
                    R.id.forms_field_editor_type_number -> FieldType.NUMBER
                    R.id.forms_field_editor_type_date -> FieldType.DATE
                    R.id.forms_field_editor_type_checkbox -> FieldType.CHECKBOX
                    else -> FieldType.TEXT
                }
                val saved = if (initial == null) {
                    WizardViewModel.newField(label = labelText.ifBlank { "(unnamed)" }, type = type)
                } else {
                    initial.copy(label = labelText.ifBlank { initial.label }, type = type)
                }
                onFieldSaved?.invoke(saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_LABEL = "label"
        private const val ARG_TYPE = "type"
        private const val ARG_REQUIRED = "required"
        private const val ARG_REUSABLE = "reusable"
        private const val ARG_CONFIDENCE = "confidence"

        /** @param existing pass null to add a new field; the field to edit otherwise. */
        fun newInstance(existing: FormField?): FieldEditorDialog {
            val dlg = FieldEditorDialog()
            if (existing != null) {
                dlg.arguments = Bundle().apply {
                    putString(ARG_ID, existing.id)
                    putString(ARG_LABEL, existing.label)
                    putString(ARG_TYPE, existing.type.id)
                    putBoolean(ARG_REQUIRED, existing.required)
                    putBoolean(ARG_REUSABLE, existing.reusable)
                    existing.confidence?.let { putDouble(ARG_CONFIDENCE, it) }
                }
            }
            return dlg
        }
    }
}
