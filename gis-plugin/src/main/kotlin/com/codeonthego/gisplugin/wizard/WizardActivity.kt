package com.codeonthego.gisplugin.wizard

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.codeonthego.gisplugin.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * Single-screen stub for ADFA-2436 C1.
 *
 * Proves that:
 *  - a plugin-defined Activity launches successfully under PluginTheme
 *  - the back button + Cancel button + Continue button each return a
 *    well-formed signal through [WizardLauncher]
 *  - the Activity finishes cleanly so the launching coroutine can resume.
 *
 * C2 will swap the body for a 3-step view-pager flow (region picker / bbox /
 * download) per ADFA-2436 §5.2.
 */
class WizardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finishWith(null)
        }

        findViewById<TextView>(R.id.title).setText(R.string.gis_wizard_step1_title)
        findViewById<TextView>(R.id.subtitle).setText(R.string.gis_wizard_step1_subtitle)
        findViewById<TextView>(R.id.proof_summary).setText(R.string.gis_wizard_proof_summary)

        findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            finishWith(null)
        }
        findViewById<MaterialButton>(R.id.btn_continue).apply {
            setText(R.string.gis_wizard_continue_proof)
            setOnClickListener {
                // C1: no real region selected — return a stub so the recipe can proceed.
                finishWith(
                    WizardResult(
                        regionId = "stub-region",
                        source = "stub"
                    )
                )
            }
        }

        // Treat back-press as cancellation, same as the Cancel button.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWith(null)
            }
        })
    }

    private fun finishWith(result: WizardResult?) {
        WizardLauncher.complete(result)
        finish()
    }

    override fun onDestroy() {
        // Belt-and-braces: if the Activity dies before the buttons fire (e.g. system
        // process kill), still wake the awaiting coroutine. complete() is idempotent
        // because CompletableDeferred only takes the first result.
        WizardLauncher.complete(null)
        super.onDestroy()
    }
}
