package org.appdevforall.cotg.corpus.nativeapp.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.nativeapp.core.LabelFormatter

class MainActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(32, 64, 32, 32)
			}
		root.addView(TextView(this).apply { text = LabelFormatter().label() })
		setContentView(root)
	}
}
