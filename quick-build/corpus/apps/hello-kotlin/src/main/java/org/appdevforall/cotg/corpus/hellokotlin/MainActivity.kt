package org.appdevforall.cotg.corpus.hellokotlin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
	private val greeter = Greeter()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root = LinearLayout(this)
		root.orientation = LinearLayout.VERTICAL
		root.gravity = Gravity.CENTER

		val greetingView = TextView(this)
		greetingView.id = ID_GREETING
		greetingView.text = greeter.greet(getString(R.string.greeting_name))
		root.addView(greetingView)

		val detailButton = Button(this)
		detailButton.text = getString(R.string.detail_label)
		detailButton.setOnClickListener {
			startActivity(Intent(this, DetailActivity::class.java))
		}
		root.addView(detailButton)

		setContentView(root)
	}

	companion object {
		const val ID_GREETING = 1001
	}
}
