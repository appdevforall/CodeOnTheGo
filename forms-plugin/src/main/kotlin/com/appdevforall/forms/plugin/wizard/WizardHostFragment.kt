package com.appdevforall.forms.plugin.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.appdevforall.forms.plugin.FormsPlugin
import com.appdevforall.forms.plugin.R
import com.google.android.material.button.MaterialButton
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-step wizard host fragment. Owns the [WizardViewModel], swaps step
 * fragments into the content frame, and gates the Continue button per-step.
 *
 * **Why a Fragment, not an Activity:** plugin APKs are loaded into the host
 * process via DexClassLoader; their `<activity>` declarations never enter the
 * host's merged AndroidManifest, so `Intent(ctx, WizardActivity::class.java)`
 * throws ActivityNotFoundException at runtime. Hosting the wizard as a
 * Fragment inside the schema panel's editor tab keeps it reachable.
 *
 * The host fragment is created and added to the parent fragment's
 * `childFragmentManager` by [com.appdevforall.forms.plugin.panel.SchemaPanelFragment]
 * when the user taps Capture. It removes itself from the back stack on
 * Finish or Cancel; the parent fragment's [androidx.fragment.app.Fragment.onResume]
 * then re-renders the schema panel from disk.
 *
 * **Step 1 → 4:**
 * 1. Capture — app/package name + (skip / take photo / pick gallery)
 * 2. Review fields — manual add/edit/delete; CV-seeded if step 1 used a photo
 * 3. Rules — required + reusable per field
 * 4. Submit — POST URL, CSV/JSON share, offline queue
 *
 * **Cancellation paths:**
 * - Hardware back on step 1 → finish wizard (no schema written).
 * - Hardware back on steps 2-4 → previous step.
 * - Cancel button at any step → finish wizard (no schema written).
 *
 * **Finish path (step 4):**
 * - Validates app/package name + at least one field + (if POST enabled) URL
 *   has http(s):// scheme.
 * - On success, hands the schema to [FormsPluginConnector.deliverCompleted],
 *   which routes through [FormsPlugin.onWizardCompleted] to write the
 *   schema into the open project.
 */
internal class WizardHostFragment : Fragment() {

    /** Step fragments share this ViewModel scoped to the host fragment. */
    val viewModel: WizardViewModel by viewModels()

    private var currentStep: WizardStep = WizardStep.CAPTURE

    private var titleView: TextView? = null
    private var backBtn: MaterialButton? = null
    private var cancelBtn: MaterialButton? = null
    private var continueBtn: MaterialButton? = null

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
    ): View = inflater.inflate(R.layout.fragment_forms_wizard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        titleView = view.findViewById(R.id.forms_wizard_step_title)
        backBtn = view.findViewById(R.id.forms_wizard_back)
        cancelBtn = view.findViewById(R.id.forms_wizard_cancel)
        continueBtn = view.findViewById(R.id.forms_wizard_continue)

        cancelBtn?.setOnClickListener { finishCanceled() }
        backBtn?.setOnClickListener { goPreviousStep() }
        continueBtn?.setOnClickListener { goNextStep() }

        // Intercept the system back button. On step 1 we cancel the wizard;
        // on steps 2-4 we step back. The callback is added via the *fragment's*
        // viewLifecycleOwner so it goes away with the view, leaving the
        // parent fragment's back-stack handling intact.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentStep.isFirst) finishCanceled() else goPreviousStep()
                }
            },
        )

        if (savedInstanceState != null) {
            currentStep = WizardStep.values().getOrNull(
                savedInstanceState.getInt(STATE_STEP, 0),
            ) ?: WizardStep.CAPTURE
        }
        renderStep(currentStep, addToBackStack = false, restoreOnly = savedInstanceState != null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, currentStep.ordinal)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        titleView = null
        backBtn = null
        cancelBtn = null
        continueBtn = null
    }

    private fun goNextStep() {
        val validationError = validateStep(currentStep)
        if (validationError != null) {
            Toast.makeText(requireContext(), validationError, Toast.LENGTH_LONG).show()
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
        // Each forward step was pushed onto the childFragmentManager back stack
        // via addToBackStack, so popping restores the previous step's fragment
        // + its instance state.
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
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
        val tx = childFragmentManager.beginTransaction()
            .replace(R.id.forms_wizard_content, fragment, "step_${step.name}")
        if (addToBackStack) tx.addToBackStack("step_${step.name}")
        tx.commit()
    }

    private fun renderChrome(step: WizardStep) {
        titleView?.text = getString(step.stringResId)
        backBtn?.visibility = if (step.isFirst) View.GONE else View.VISIBLE
        continueBtn?.text = getString(
            if (step.isLast) R.string.forms_wizard_finish
            else R.string.forms_wizard_continue,
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
                    return getString(R.string.forms_template_validation_appname)
                }
                val pkg = viewModel.packageName.value.orEmpty().trim()
                // Validate package name as a Java/Android package: lowercase
                // letter-led segments separated by dots, at least two
                // segments. Stricter than Manifest.applicationId permits
                // (which allows uppercase) so user input that wouldn't
                // compile cleanly gets caught here instead of at Gradle
                // time, downstream of the wizard.
                if (pkg.isNotEmpty() && !PACKAGE_NAME_REGEX.matches(pkg)) {
                    return getString(R.string.forms_template_validation_package)
                }
                null
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
        Toast.makeText(requireContext(), R.string.forms_wizard_canceled, Toast.LENGTH_SHORT).show()
        dismissSelf()
    }

    private fun finishCompleted() {
        val schema = viewModel.snapshot()
        // Disable Continue while the write is in flight so the user can't
        // double-fire on slow flash. We don't disable Cancel — bailing
        // before the write resolves is fine; the .tmp staging file (if any)
        // is invisible to the panel.
        continueBtn?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // Disk I/O — JSON write + atomic rename — runs on IO. The
            // toast and dismiss happen back on Main once the write resolves.
            val writtenPath = withContext(Dispatchers.IO) {
                FormsPluginConnector.deliverCompleted(schema)
            }
            val toastResId = if (writtenPath != null) {
                R.string.forms_panel_schema_saved
            } else {
                R.string.forms_panel_schema_save_failed
            }
            Toast.makeText(requireContext(), toastResId, Toast.LENGTH_LONG).show()
            dismissSelf()
        }
    }

    /**
     * Pop ourselves off the parent fragment's back stack. The parent
     * (SchemaPanelFragment) hosts us via childFragmentManager + addToBackStack,
     * so popping returns the user to the panel content. Doing this from a
     * UI-thread callback is safe; if the parent is already detached we just
     * skip silently.
     */
    private fun dismissSelf() {
        val parent = parentFragment ?: return
        val pfm = parent.childFragmentManager
        if (pfm.backStackEntryCount > 0) {
            pfm.popBackStack()
        } else {
            pfm.beginTransaction().remove(this).commitAllowingStateLoss()
        }
    }

    companion object {
        private const val STATE_STEP = "forms_wizard.current_step"

        /**
         * Lowercase letter-led segments, at least two, dot-separated.
         * Tighter than Android's applicationId rules (which allow
         * uppercase) so we surface the issue at Continue time instead of
         * at Gradle time; matches the convention used by templates-impl's
         * package validation.
         *
         * Exposed as `internal` so JVM tests can pin the precedence
         * without instantiating the Fragment.
         */
        internal val PACKAGE_NAME_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    }
}
