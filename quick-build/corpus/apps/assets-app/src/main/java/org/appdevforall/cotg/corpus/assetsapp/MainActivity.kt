package org.appdevforall.cotg.corpus.assetsapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
	private val reader = AssetReader()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val view = TextView(this)
		view.id = ID_MESSAGE
		view.text = reader.readMessage(assets)
		setContentView(view)
	}

	companion object {
		const val ID_MESSAGE = 3001
	}
}
