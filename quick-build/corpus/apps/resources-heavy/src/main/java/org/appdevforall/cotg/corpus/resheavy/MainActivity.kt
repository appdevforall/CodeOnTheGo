package org.appdevforall.cotg.corpus.resheavy

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout

class MainActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		val container = findViewById<LinearLayout>(R.id.main_container)
		val header = HeaderViewBuilder().build(LayoutInflater.from(this), container)
		container.addView(header, 0)
	}
}
