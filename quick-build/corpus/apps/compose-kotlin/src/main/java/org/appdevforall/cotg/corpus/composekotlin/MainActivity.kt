package org.appdevforall.cotg.corpus.composekotlin

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Deliberately a plain-view Activity: the corpus classpath carries only the Compose
 * RUNTIME (androidx.compose.runtime), which is what the compiler plugin needs -- not the
 * UI stack (ui/foundation/material) a setContent screen would pull in. The Compose
 * surface under test lives in Composables.kt.
 */
class MainActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root = LinearLayout(this)
		root.orientation = LinearLayout.VERTICAL
		root.gravity = Gravity.CENTER

		val label = TextView(this)
		label.id = ID_LABEL
		label.text = GreetingFormatter.format(getString(R.string.greeting_name))
		root.addView(label)

		setContentView(root)
	}

	companion object {
		const val ID_LABEL = 1001
	}
}
