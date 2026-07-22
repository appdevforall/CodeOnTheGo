package com.andihasan.jcomposecogo.corpusharness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.andihasan.jcomposecogo.ui.screen.home.HomeUiState

/**
 * Corpus harness entry point: exercises a real JComposeCoGo subgraph
 * (HomeUiState) with no Compose/navigation wiring. See README.md.
 *
 * Deliberately does not construct HomeViewModel -- androidx.lifecycle.ViewModel's
 * real behavior needs a ViewModelStore/SavedStateHandle the harness does not set
 * up; HomeUiState alone is what each edit here changes.
 */
class JComposeCoGoHostActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val state = HomeUiState()

		val view = TextView(this)
		view.id = ID_SUMMARY
		view.text = "state=$state"
		setContentView(view)
	}

	companion object {
		const val ID_SUMMARY = 8001
	}
}
