package org.appdevforall.cotg.corpus.mixedlang.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.mixedlang.R
import org.appdevforall.cotg.corpus.mixedlang.core.JavaPresenter
import org.appdevforall.cotg.corpus.mixedlang.core.OrderService

class MainActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(32, 64, 32, 32)
			}

		val total = OrderService().total(2, 3)
		root.addView(TextView(this).apply { text = getString(R.string.order_total_label, total) })
		root.addView(TextView(this).apply { text = JavaPresenter().present(2) })

		setContentView(root)
	}
}
