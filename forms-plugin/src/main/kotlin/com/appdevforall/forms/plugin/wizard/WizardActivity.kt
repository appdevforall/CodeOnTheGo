package com.appdevforall.forms.plugin.wizard

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.appdevforall.forms.plugin.R
import com.google.android.material.button.MaterialButton

/**
 * Multi-step wizard host. Owns the [WizardViewModel], swaps step fragments
 * into the content frame, and gates the Continue button per-step.
 *
 * **Step 1 → 4:**
 * 1. Capture — app/package name + (skip / take photo / pick gallery, photo
 *    paths inert in C2)
 * 2. Review fields — manual add/edit/delete in C2; CV-seeded in C3
 * 3. Rules — required + reusable per field
 * 4. Submit — POST URL, CSV/JSON share, offline queue
 *
 * **Cancellation paths:**
 * - Hardware back button on step 1 finishes the activity (no template
 *   registered).
 * - Hardware back button on steps 2-4 navigates to the previous step.
 * - Cancel button at any step finishes the activity (no template
 *   registered).
 *
 * **Finish path (step 4):**
 * - Validates app/package name + at least one field + (if POST enabled)
 *   the URL has http(s):// scheme.
 * - On success, hands the schema to [FormsPluginConnector.deliverCompleted],
 *   which routes through [com.appdevforall.forms.plugin.FormsPlugin.onWizardCompleted]
 *   to register the per-instance .cgt and surface a toast.
 */
class WizardActivity : AppCompatActivity() {

    private val viewModel: WizardViewModel by viewModels()

    private var currentStep: WizardStep = WizardStep.CAPTURE

    private lateinit var titleView: TextView
    private lateinit var backBtn: MaterialButton
    private lateinit var cancelBtn: MaterialButton
    private lateinit var continueBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forms_wizard)

        titleView = findViewById(R.id.forms_wizard_step_title)
        backBtn = findViewById(R.id.forms_wizard_back)
        cancelBtn = findViewById(R.id.forms_wizard_cancel)
        continueBtn = findViewById(R.id.forms_wizard_continue)

        cancelBtn.setOnClickListener { finishCanceled() }
        backBtn.setOnClickListener { goPreviousStep() }
        continueBtn.setOnClickListener { goNextStep() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentStep.isFirst) finishCanceled() else goPreviousStep()
            }
        })

        if (savedInstanceState != null) {
            currentStep = WizardStep.values().getOrNull(
                savedInstanceState.getInt(STATE_STEP, 0)
            ) ?: WizardStep.CAPTURE
        }
        renderStep(currentStep, addToBackStack = false, restoreOnly = savedInstanceState != null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, currentStep.ordinal)
    }

    private fun goNextStep() {
        val validationError = validateStep(currentStep)
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
            return
        }
        if (currentStep.isLast) {
            finishCompleted()
            return
        }
        val next = currentStep.next() ?: return
        currentStep = next
        renderStep(next, addToBackStack = true, restoreOnly = false)
    }

    private fun goPreviousStep() {
        val prev = currentStep.previous() ?: return
        currentStep = prev
        // Each forward step was pushed onto the FragmentManager back stack via
        // addToBackStack, so popping restores the previous step's fragment +
        // its instance state.
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            renderChrome(prev)
        } else {
            // Defensive fallback: re-render explicitly. Shouldn't happen since
            // we only reach here for steps 2-4, all of which were addToBackStack.
            renderStep(prev, addToBackStack = false, restoreOnly = false)
        }
    }

    /** @param restoreOnly true when re-rendering after a config change — skip
     *  re-attaching the fragment because Android already restored it. */
    private fun renderStep(step: WizardStep, addToBackStack: Boolean, restoreOnly: Boolean) {
        renderChrome(step)
        if (restoreOnly) return
        val fragment: Fragment = when (step) {
            WizardStep.CAPTURE -> Step1CaptureFragment()
            WizardStep.REVIEW_FIELDS -> Step2ReviewFieldsFragment()
            WizardStep.RULES -> Step3RulesFragment()
            WizardStep.SUBMIT -> Step4SubmitFragment()
        }
        val tx = supportFragmentManager.beginTransaction()
            .replace(R.id.forms_wizard_content, fragment, "step_${step.name}")
        if (addToBackStack) tx.addToBackStack("step_${step.name}")
        tx.commit()
    }

    private fun renderChrome(step: WizardStep) {
        titleView.text = getString(step.stringResId)
        backBtn.visibility = if (step.isFirst) View.GONE else View.VISIBLE
        continueBtn.text = getString(
            if (step.isLast) R.string.forms_wizard_finish
            else R.string.forms_wizard_continue
        )
    }

    /**
     * Validate the just-completed step before advancing. Returns null if OK
     * or a user-facing error message string otherwise.
     */
    private fun validateStep(step: WizardStep): String? {
        return when (step) {
            WizardStep.CAPTURE -> {
                val name = viewModel.appName.value.orEmpty().trim()
                if (!name.any { it.isLetterOrDigit() }) {
                    getString(R.string.forms_template_validation_appname)
                } else null
            }
            WizardStep.REVIEW_FIELDS -> {
                if (viewModel.fields.value.orEmpty().isEmpty()) {
                    getString(R.string.forms_template_validation_no_fields)
                } else null
            }
            WizardStep.RULES -> null  // every state is valid
            WizardStep.SUBMIT -> {
                val s = viewModel.submit.value
                val url = s?.postUrl?.trim()
                if (url != null && url.isNotEmpty() &&
                    !url.startsWith("http://") && !url.startsWith("https://")
                ) {
                    getString(R.string.forms_template_validation_endpoint)
                } else null
            }
        }
    }

    private fun finishCanceled() {
        FormsPluginConnector.deliverCanceled()
        Toast.makeText(this, R.string.forms_wizard_canceled, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun finishCompleted() {
        val schema = viewModel.snapshot()
        val written = FormsPluginConnector.deliverCompleted(schema)
        val toastResId = if (written != null) {
            R.string.forms_panel_schema_saved
        } else {
            R.string.forms_panel_schema_save_failed
        }
        Toast.makeText(this, toastResId, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val STATE_STEP = "forms_wizard.current_step"
    }
}
