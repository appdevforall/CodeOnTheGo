package org.appdevforall.cotg.corpus.hellokotlin

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class DetailActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val view = TextView(this)
		view.id = ID_DETAIL
		view.text = "Detail screen: " + Greeter().greet("Detail visitor")
		setContentView(view)
	}

	companion object {
		const val ID_DETAIL = 1002
	}
}
