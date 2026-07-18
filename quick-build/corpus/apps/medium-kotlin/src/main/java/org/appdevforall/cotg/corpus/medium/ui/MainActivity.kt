package org.appdevforall.cotg.corpus.medium.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.medium.R
import org.appdevforall.cotg.corpus.medium.core.ComponentRegistry
import org.appdevforall.cotg.corpus.medium.data.Order
import org.appdevforall.cotg.corpus.medium.data.OrderProcessor
import org.appdevforall.cotg.corpus.medium.data.Product
import org.appdevforall.cotg.corpus.medium.data.User
import org.appdevforall.cotg.corpus.medium.formatters.NumberFormatter

class MainActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				gravity = Gravity.CENTER_HORIZONTAL
				setPadding(32, 64, 32, 32)
			}

		val greeting = ComponentRegistry.greeterFor("formal").greet("Ada")
		val greetingView = TextView(this).apply { text = greeting }
		root.addView(greetingView)

		val user = User(id = 1, name = "Ada", email = "ada@example.com")
		val order =
			Order(
				id = 42,
				user = user,
				items =
					listOf(
						Product(sku = "SKU-1", label = "Widget", priceCents = 500),
						Product(sku = "SKU-2", label = "Gadget", priceCents = 1200),
					),
			)
		val total = OrderProcessor().total(order)
		val totalView =
			TextView(this).apply {
				text = getString(R.string.order_total_label, NumberFormatter().format(total))
			}
		root.addView(totalView)

		val detailsButton =
			Button(this).apply {
				text = getString(R.string.details_button_label)
				setOnClickListener { startActivity(Intent(this@MainActivity, DetailsActivity::class.java)) }
			}
		root.addView(detailsButton)

		val settingsButton =
			Button(this).apply {
				text = getString(R.string.settings_button_label)
				setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
			}
		root.addView(settingsButton)

		setContentView(root)
	}
}
