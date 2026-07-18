package org.appdevforall.cotg.corpus.multiactivity.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.multiactivity.R
import org.appdevforall.cotg.corpus.multiactivity.data.ItemRepository

class ListActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(32, 64, 32, 32)
			}

		for (item in ItemRepository().all()) {
			val view = TextView(this).apply { text = getString(R.string.item_line_label, item.id, item.name) }
			root.addView(view)
		}

		setContentView(root)
	}
}
