package com.appdevforall.forms.plugin.wizard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.appdevforall.forms.plugin.FieldType
import com.appdevforall.forms.plugin.FormField
import com.appdevforall.forms.plugin.FormSchema
import com.appdevforall.forms.plugin.SubmitConfig
import java.util.UUID

/**
 * Holds the wizard's working state across step changes and configuration
 * changes. The fragments observe [fields], [submit], and the user-supplied
 * app metadata; the host Activity reads the consolidated [snapshot] when the
 * user presses Finish.
 *
 * State is kept simple — no Flows / Channels — so the wizard works in
 * pre-Compose AppCompat without dragging in extra deps. LiveData is what
 * the existing keystore-generator-plugin uses internally.
 */
class WizardViewModel : ViewModel() {

    private val _appName = MutableLiveData("FormApp")
    val appName: LiveData<String> get() = _appName

    private val _packageName = MutableLiveData("com.example.formapp")
    val packageName: LiveData<String> get() = _packageName

    private val _photoPath = MutableLiveData<String?>(null)
    val photoPath: LiveData<String?> get() = _photoPath

    private val _fields = MutableLiveData<List<FormField>>(emptyList())
    val fields: LiveData<List<FormField>> get() = _fields

    private val _submit = MutableLiveData(SubmitConfig())
    val submit: LiveData<SubmitConfig> get() = _submit

    fun setAppName(name: String) {
        _appName.value = name
    }

    fun setPackageName(pkg: String) {
        _packageName.value = pkg
    }

    fun setPhotoPath(path: String?) {
        _photoPath.value = path
    }

    fun setSubmit(config: SubmitConfig) {
        _submit.value = config
    }

    /**
     * Replace the entire fields list. Useful when Step 2 receives CV-detected
     * fields wholesale.
     */
    fun replaceFields(newFields: List<FormField>) {
        _fields.value = newFields
    }

    fun addField(field: FormField) {
        _fields.value = (_fields.value.orEmpty()) + field
    }

    fun updateField(field: FormField) {
        _fields.value = _fields.value.orEmpty().map {
            if (it.id == field.id) field else it
        }
    }

    fun removeField(fieldId: String) {
        _fields.value = _fields.value.orEmpty().filterNot { it.id == fieldId }
    }

    /**
     * Mutate just the rule flags on an existing field (Step 3's surface).
     */
    fun setFieldRules(fieldId: String, required: Boolean, reusable: Boolean) {
        _fields.value = _fields.value.orEmpty().map {
            if (it.id == fieldId) it.copy(required = required, reusable = reusable) else it
        }
    }

    fun snapshot(): FormSchema = FormSchema(
        appName = _appName.value.orEmpty().trim(),
        packageName = _packageName.value.orEmpty().trim(),
        fields = _fields.value.orEmpty(),
        submit = _submit.value ?: SubmitConfig(),
    )

    companion object {
        /** Helper for fragments to mint a new field id. */
        fun newFieldId(): String = "f_" + UUID.randomUUID().toString().take(8)

        fun newField(label: String, type: FieldType): FormField =
            FormField(id = newFieldId(), label = label, type = type)
    }
}
