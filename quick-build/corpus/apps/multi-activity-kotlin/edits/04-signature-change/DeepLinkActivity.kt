package org.appdevforall.cotg.corpus.multiactivity.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.multiactivity.R
import org.appdevforall.cotg.corpus.multiactivity.data.ItemRepository

/** singleTop + a VIEW deep-link intent-filter (see AndroidManifest.xml); onNewIntent handles
 * re-launch with a fresh URI instead of stacking a duplicate instance. */
class DeepLinkActivity : Activity() {

	private lateinit var summaryView: TextView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(32, 64, 32, 32)
		}
		summaryView = TextView(this)
		root.addView(summaryView)
		setContentView(root)
		renderFor(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		renderFor(intent)
	}

	private fun renderFor(intent: Intent) {
		val itemId = intent.data?.lastPathSegment?.toIntOrNull() ?: -1
		val label = ItemRepository().findById(itemId).name
		summaryView.text = getString(R.string.deeplink_item_label, label)
	}
}
