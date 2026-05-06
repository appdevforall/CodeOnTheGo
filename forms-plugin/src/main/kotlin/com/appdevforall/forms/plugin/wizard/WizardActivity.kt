package com.appdevforall.forms.plugin.wizard

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.appdevforall.forms.plugin.FormSchema
import com.appdevforall.forms.plugin.R
import com.google.android.material.button.MaterialButton

/**
 * Multi-step wizard for capturing a form's schema.
 *
 * **C1 scope (this commit):** trivial one-screen Activity that just verifies
 * plumbing — the sidebar can launch it, the user can see it, the Cancel and
 * Continue buttons close it cleanly. Continue currently calls
 * [FormsPluginConnector.onWizardCompleted] with a placeholder schema; that
 * routes through the static-template registration path so we exercise the
 * end-to-end registerTemplate flow with a real (if uninteresting) cgt.
 *
 * **C2 will replace this** with a 4-step Fragment-driven flow (capture /
 * review / rules / submit) per the mockup. The Activity wiring (back-button
 * cancel, result delivery via [FormsPluginConnector]) stays the same.
 *
 * Cancellation: hardware/system back button or the explicit Cancel button
 * both finish the Activity without invoking the connector — registerTemplate
 * is never called, so the user lands back in the IDE with no side effects.
 */
class WizardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forms_wizard)

        val title = findViewById<TextView>(R.id.forms_wizard_step_title)
        val contentFrame = findViewById<View>(R.id.forms_wizard_content)
        val backBtn = findViewById<MaterialButton>(R.id.forms_wizard_back)
        val cancelBtn = findViewById<MaterialButton>(R.id.forms_wizard_cancel)
        val continueBtn = findViewById<MaterialButton>(R.id.forms_wizard_continue)

        // C1: a single placeholder step. C2 wires up real fragments and a
        // ViewModel-driven step controller into contentFrame.
        title.text = getString(R.string.forms_wizard_step1_title)
        contentFrame.setBackgroundColor(0)
        backBtn.visibility = View.GONE

        cancelBtn.setOnClickListener { finishCanceled() }
        continueBtn.setOnClickListener { finishWithPlaceholderSchema() }

        // Hardware back-button = cancel, same as Cancel button.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishCanceled()
            }
        })
    }

    private fun finishCanceled() {
        FormsPluginConnector.deliverCanceled()
        finish()
    }

    private fun finishWithPlaceholderSchema() {
        // C1 placeholder schema. C2 replaces this with the user-built schema.
        val schema = FormSchema(
            appName = "FormApp",
            packageName = "com.example.formapp",
            fields = emptyList(),
            submit = com.appdevforall.forms.plugin.SubmitConfig(),
        )
        FormsPluginConnector.deliverCompleted(schema)
        finish()
    }
}
