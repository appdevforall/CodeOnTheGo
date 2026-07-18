package org.appdevforall.cotg.corpus.multiactivity.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.multiactivity.R
import org.appdevforall.cotg.corpus.multiactivity.core.ResultCodeFormatter

class MainActivity : Activity() {

	private lateinit var resultView: TextView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			setPadding(32, 64, 32, 32)
		}

		root.addView(TextView(this).apply { text = getString(R.string.main_title_label) })

		val listButton = Button(this).apply {
			text = getString(R.string.open_list_label)
			setOnClickListener { startActivity(Intent(this@MainActivity, ListActivity::class.java)) }
		}
		root.addView(listButton)

		val detailButton = Button(this).apply {
			text = getString(R.string.open_detail_for_result_label)
			setOnClickListener {
				val intent = Intent(this@MainActivity, DetailActivity::class.java)
				intent.putExtra(DetailActivity.EXTRA_ITEM_ID, 1)
				startActivityForResult(intent, REQUEST_DETAIL)
			}
		}
		root.addView(detailButton)

		resultView = TextView(this).apply { text = getString(R.string.no_result_yet_label) }
		root.addView(resultView)

		val deepLinkButton = Button(this).apply {
			text = getString(R.string.open_deeplink_label)
			setOnClickListener {
				startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("cotgcorpus://detail/2")))
			}
		}
		root.addView(deepLinkButton)

		setContentView(root)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == REQUEST_DETAIL) {
			val label = data?.getStringExtra(DetailActivity.EXTRA_RESULT_LABEL) ?: "QB_NO_DATA_MARKER_V2"
			resultView.text =
				getString(R.string.result_summary_label, ResultCodeFormatter().describe(resultCode), label)
		}
	}

	companion object {
		private const val REQUEST_DETAIL = 100
	}
}
