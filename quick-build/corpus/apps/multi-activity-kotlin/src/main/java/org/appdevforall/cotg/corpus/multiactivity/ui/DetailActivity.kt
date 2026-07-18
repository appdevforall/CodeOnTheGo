package org.appdevforall.cotg.corpus.multiactivity.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.multiactivity.R
import org.appdevforall.cotg.corpus.multiactivity.data.ItemRepository

class DetailActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val itemId = intent.getIntExtra(EXTRA_ITEM_ID, -1)
		val item = ItemRepository().findById(itemId)
		val label = item?.name ?: getString(R.string.unknown_item_label)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(32, 64, 32, 32)
			}
		root.addView(TextView(this).apply { text = getString(R.string.detail_title_label, label) })

		val confirmButton =
			Button(this).apply {
				text = getString(R.string.confirm_button_label)
				setOnClickListener {
					setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_LABEL, label))
					finish()
				}
			}
		root.addView(confirmButton)

		setContentView(root)
	}

	companion object {
		const val EXTRA_ITEM_ID = "extra_item_id"
		const val EXTRA_RESULT_LABEL = "extra_result_label"
	}
}
