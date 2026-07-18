package org.appdevforall.cotg.corpus.fanout.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.fanout.formatters.CartTotalFormatter
import org.appdevforall.cotg.corpus.fanout.formatters.OrderCountFormatter
import org.appdevforall.cotg.corpus.fanout.formatters.WalletBalanceFormatter
import org.appdevforall.cotg.corpus.fanout.screens.CartScreenLabel
import org.appdevforall.cotg.corpus.fanout.screens.HomeScreenLabel
import org.appdevforall.cotg.corpus.fanout.screens.WalletScreenLabel

class MainActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(32, 64, 32, 32)
			}

		val labels =
			listOf(
				HomeScreenLabel().title(),
				CartScreenLabel().title(),
				WalletScreenLabel().title(),
			)
		for (label in labels) {
			root.addView(TextView(this).apply { text = label })
		}

		val counters =
			listOf(
				OrderCountFormatter().describe(3),
				CartTotalFormatter().describe(2),
				WalletBalanceFormatter().describe(4200),
			)
		for (counter in counters) {
			root.addView(TextView(this).apply { text = counter })
		}

		setContentView(root)
	}
}
