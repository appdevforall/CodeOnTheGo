package com.appdevforall.forms.plugin.wizard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.appdevforall.forms.plugin.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Step 1 of 4 — Capture form.
 *
 * C2: photo capture is disabled (Take photo / Pick gallery buttons are
 * inert). The user is expected to tap **Skip — lay out manually** and then
 * add fields by hand in step 2. C3 wires up the camera + gallery + CV intent.
 *
 * The app-name and package-name editors live here rather than on the
 * template-instance details screen because the wizard runs *before* the user
 * picks the template; the values feed straight into the per-instance .cgt's
 * default placeholders.
 */
class Step1CaptureFragment : Fragment() {

    private val viewModel: WizardViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_wizard_step1, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val skip = view.findViewById<MaterialButton>(R.id.forms_wizard_step1_skip)
        val takePhoto = view.findViewById<MaterialButton>(R.id.forms_wizard_step1_take_photo)
        val pickGallery = view.findViewById<MaterialButton>(R.id.forms_wizard_step1_pick_gallery)
        val appName = view.findViewById<TextInputEditText>(R.id.forms_wizard_step1_app_name)
        val pkgName = view.findViewById<TextInputEditText>(R.id.forms_wizard_step1_package_name)

        // C2: skip is the only action; C3 will enable photo paths.
        takePhoto.isEnabled = false
        pickGallery.isEnabled = false
        skip.setOnClickListener {
            // No-op — pressing Skip just lets the user move on via the Continue
            // button in the host. We update photoPath to null so step 2 knows
            // there's no CV input to seed.
            viewModel.setPhotoPath(null)
        }

        appName.setText(viewModel.appName.value.orEmpty())
        pkgName.setText(viewModel.packageName.value.orEmpty())

        appName.addTextChangedListener(simpleWatcher { viewModel.setAppName(it) })
        pkgName.addTextChangedListener(simpleWatcher { viewModel.setPackageName(it) })
    }

    private fun simpleWatcher(onChange: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString().orEmpty())
            }
        }
    }
}
