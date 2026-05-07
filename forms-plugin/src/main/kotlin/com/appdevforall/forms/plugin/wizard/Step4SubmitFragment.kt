package com.appdevforall.forms.plugin.wizard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.appdevforall.forms.plugin.FormsPlugin
import com.appdevforall.forms.plugin.R
import com.appdevforall.forms.plugin.SubmitConfig
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import android.widget.RadioButton
import android.widget.RadioGroup

/**
 * Step 4 of 4 — Where does data go?
 *
 * Captures the [SubmitConfig] for the generated app: HTTP POST URL (with
 * JSON / form-encoded toggle), CSV share, JSON share, offline queue.
 *
 * Validation is intentionally light here; the host Activity does final
 * validation when the user presses Finish (URL must start with http:// or
 * https:// if POST is enabled).
 */
class Step4SubmitFragment : Fragment() {

    private val viewModel: WizardViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
    )

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        return PluginFragmentHelper.getPluginInflater(
            FormsPlugin.PLUGIN_ID,
            super.onGetLayoutInflater(savedInstanceState),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_wizard_step4, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val postEnable = view.findViewById<MaterialCheckBox>(R.id.forms_wizard_step4_post_enable)
        val postUrlLayout = view.findViewById<TextInputLayout>(R.id.forms_wizard_step4_post_url_layout)
        val postUrl = view.findViewById<TextInputEditText>(R.id.forms_wizard_step4_post_url)
        val postFormat = view.findViewById<RadioGroup>(R.id.forms_wizard_step4_post_format)
        val postJson = view.findViewById<RadioButton>(R.id.forms_wizard_step4_post_json)
        val csvShare = view.findViewById<MaterialCheckBox>(R.id.forms_wizard_step4_csv_share)
        val jsonShare = view.findViewById<MaterialCheckBox>(R.id.forms_wizard_step4_json_share)
        val offlineQueue = view.findViewById<MaterialCheckBox>(R.id.forms_wizard_step4_offline_queue)

        // Seed from existing state (might be re-entry after Back).
        viewModel.submit.value?.let { state ->
            postEnable.isChecked = state.postUrl != null
            postUrl.setText(state.postUrl.orEmpty())
            postJson.isChecked = state.postAsJson
            csvShare.isChecked = state.allowCsvShare
            jsonShare.isChecked = state.allowJsonShare
            offlineQueue.isChecked = state.offlineQueue
        }
        applyPostEnabledVisibility(postEnable.isChecked, postUrlLayout, postFormat)

        val push: () -> Unit = {
            val urlStr = postUrl.text?.toString().orEmpty().trim()
            viewModel.setSubmit(
                SubmitConfig(
                    postUrl = if (postEnable.isChecked && urlStr.isNotEmpty()) urlStr else null,
                    postAsJson = postJson.isChecked,
                    allowCsvShare = csvShare.isChecked,
                    allowJsonShare = jsonShare.isChecked,
                    offlineQueue = offlineQueue.isChecked,
                )
            )
        }

        postEnable.setOnCheckedChangeListener { _, checked ->
            applyPostEnabledVisibility(checked, postUrlLayout, postFormat)
            push()
        }
        postUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { push() }
        })
        postFormat.setOnCheckedChangeListener { _, _ -> push() }
        csvShare.setOnCheckedChangeListener { _, _ -> push() }
        jsonShare.setOnCheckedChangeListener { _, _ -> push() }
        offlineQueue.setOnCheckedChangeListener { _, _ -> push() }
    }

    private fun applyPostEnabledVisibility(
        enabled: Boolean,
        urlLayout: View,
        formatGroup: View,
    ) {
        val v = if (enabled) View.VISIBLE else View.GONE
        urlLayout.visibility = v
        formatGroup.visibility = v
    }
}
