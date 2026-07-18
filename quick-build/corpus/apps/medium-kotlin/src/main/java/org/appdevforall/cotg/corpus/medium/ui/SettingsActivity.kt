package org.appdevforall.cotg.corpus.medium.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.medium.R
import org.appdevforall.cotg.corpus.medium.core.ComponentRegistry

class SettingsActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(32, 64, 32, 32)
			}

		val emailValid = ComponentRegistry.validatorFor("email").isValid("ada@example.com")
		val lengthValid = ComponentRegistry.validatorFor("length").isValid("Ada")

		val emailView =
			TextView(this).apply {
				text = getString(R.string.validation_result_label, "email", emailValid.toString())
			}
		root.addView(emailView)

		val lengthView =
			TextView(this).apply {
				text = getString(R.string.validation_result_label, "length", lengthValid.toString())
			}
		root.addView(lengthView)

		setContentView(root)
	}
}
