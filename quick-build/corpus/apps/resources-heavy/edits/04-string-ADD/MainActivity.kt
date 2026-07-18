package org.appdevforall.cotg.corpus.resheavy

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		val container = findViewById<LinearLayout>(R.id.main_container)
		val header = HeaderViewBuilder().build(LayoutInflater.from(this), container)
		container.addView(header, 0)

		// New in this edit: references the just-added R.string.new_feature_label, which only
		// exists after aapt2 regenerates R (a new resource ID, not a value change).
		val marker = "QB_MARKER_STEP4"
		val newFeatureView = TextView(this).apply {
			text = "$marker:" + getString(R.string.new_feature_label)
		}
		container.addView(newFeatureView)
	}
}
