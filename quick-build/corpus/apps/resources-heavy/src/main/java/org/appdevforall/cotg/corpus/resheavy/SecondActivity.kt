package org.appdevforall.cotg.corpus.resheavy

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout

class SecondActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_second)

		val container = findViewById<LinearLayout>(R.id.second_container)
		val footer = FooterViewBuilder().build(LayoutInflater.from(this), container)
		container.addView(footer)
	}
}
