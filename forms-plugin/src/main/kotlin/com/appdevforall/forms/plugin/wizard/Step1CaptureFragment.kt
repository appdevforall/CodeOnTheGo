package com.appdevforall.forms.plugin.wizard

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.appdevforall.forms.plugin.FormsPlugin
import com.appdevforall.forms.plugin.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import java.io.File

/**
 * Step 1 of 4 — Capture form.
 *
 * Two paths through this step:
 *
 * 1. **Take photo** launches the host IDE's `ComputerVisionActivity` via
 *    explicit `ComponentName` (the plugin doesn't have compile-time access
 *    to the cv-image-to-xml module — they ship in the same process via
 *    DexClassLoader, so the explicit class name is reachable at runtime).
 *    The CV Activity handles photo capture, ML detection, and XML
 *    generation; it returns an Android-layout XML string. We parse the XML
 *    via [CvLayoutParser] and seed the field list, replacing whatever was
 *    there before.
 *
 * 2. **Skip — lay out manually** clears any seeded fields and lets the
 *    user build the schema by hand in step 2.
 *
 * The CV Activity contract is brittle (Intent extras + result XML strings,
 * not a clean library API) — see plan §5c open question 10.1. If the host
 * IDE doesn't ship `ComputerVisionActivity` (older builds, future
 * refactors), the Take photo button surfaces a toast and the user falls
 * back to manual entry.
 */
class Step1CaptureFragment : Fragment() {

    // Scope the ViewModel to the WizardHostFragment so all four step
    // fragments share the same instance. activityViewModels() doesn't work
    // because the host is no longer an Activity.
    private val viewModel: WizardViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
    )

    private var cvStatus: TextView? = null

    private val cvLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleCvResult(result.resultCode, result.data)
    }

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
    ): View = inflater.inflate(R.layout.fragment_wizard_step1, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val skip = view.findViewById<MaterialButton>(R.id.forms_wizard_step1_skip)
        val takePhoto = view.findViewById<MaterialButton>(R.id.forms_wizard_step1_take_photo)
        val appName = view.findViewById<TextInputEditText>(R.id.forms_wizard_step1_app_name)
        val pkgName = view.findViewById<TextInputEditText>(R.id.forms_wizard_step1_package_name)
        cvStatus = view.findViewById(R.id.forms_wizard_step1_cv_status)

        takePhoto.setOnClickListener { launchCv() }
        skip.setOnClickListener {
            viewModel.setPhotoPath(null)
            // Don't drop existing fields the user may have already added — Skip
            // is a "no new CV input" signal, not a destructive reset.
            setStatus("Manual entry. Add fields in step 2.")
        }

        appName.setText(viewModel.appName.value.orEmpty())
        pkgName.setText(viewModel.packageName.value.orEmpty())

        appName.addTextChangedListener(simpleWatcher { viewModel.setAppName(it) })
        pkgName.addTextChangedListener(simpleWatcher { viewModel.setPackageName(it) })

        // If the user came back from step 2, reflect any pre-seeded fields.
        viewModel.fields.value?.let { fields ->
            if (fields.isNotEmpty()) setStatus("${fields.size} field(s) ready for review.")
        }
    }

    private fun launchCv() {
        val ctx = requireContext()
        val intent = buildCvIntent(ctx) ?: run {
            Toast.makeText(
                ctx,
                "Computer Vision capture isn't available in this build of the IDE.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        try {
            cvLauncher.launch(intent)
        } catch (t: Throwable) {
            Toast.makeText(
                ctx,
                "Failed to launch the CV capture activity. Use Skip and add fields manually.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun buildCvIntent(ctx: Context): Intent? {
        // The CV activity expects a layout-file path + name to write back to;
        // we just want the returned XML string in onActivityResult, so a
        // throwaway file under the plugin's cache dir works.
        val tempLayoutDir = File(ctx.cacheDir, "forms-plugin-cv").also { it.mkdirs() }
        val tempFile = File(tempLayoutDir, "form_capture.xml")
        val cvComponent = ComponentName(
            ctx.packageName,
            CV_ACTIVITY_CLASS_NAME,
        )
        return try {
            Intent().setComponent(cvComponent).apply {
                putExtra(EXTRA_LAYOUT_FILE_PATH, tempFile.absolutePath)
                putExtra(EXTRA_LAYOUT_FILE_NAME, tempFile.name)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun handleCvResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            setStatus("CV canceled. Use Skip if you want to lay out manually.")
            return
        }
        val xml = data.getStringExtra(RESULT_GENERATED_XML).orEmpty()
        if (xml.isBlank()) {
            setStatus("CV returned no XML. Add fields manually in step 2.")
            return
        }
        val fields = CvLayoutParser.parse(xml)
        if (fields.isEmpty()) {
            setStatus("No fields detected. Edit / add manually in step 2.")
            return
        }
        viewModel.replaceFields(fields)
        viewModel.setPhotoPath(data.getStringExtra(EXTRA_LAYOUT_FILE_PATH))
        setStatus("${fields.size} field(s) detected. Review them in step 2.")
    }

    private fun setStatus(text: String) {
        cvStatus?.let {
            it.text = text
            it.visibility = View.VISIBLE
        }
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

    companion object {
        // Mirrors ComputerVisionActivity.EXTRA_LAYOUT_FILE_PATH /
        // EXTRA_LAYOUT_FILE_NAME / RESULT_GENERATED_XML. Kept as duplicated
        // string constants because the plugin can't compileOnly-link to
        // cv-image-to-xml without becoming part of the IDE's main classpath.
        private const val CV_ACTIVITY_CLASS_NAME =
            "org.appdevforall.codeonthego.computervision.ui.ComputerVisionActivity"
        private const val EXTRA_LAYOUT_FILE_PATH = "com.example.images.LAYOUT_FILE_PATH"
        private const val EXTRA_LAYOUT_FILE_NAME = "com.example.images.LAYOUT_FILE_NAME"
        private const val RESULT_GENERATED_XML = "ide.uidesigner.generatedXml"
    }
}
