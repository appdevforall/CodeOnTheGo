package org.appdevforall.cotg.corpus.medium.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.medium.R
import org.appdevforall.cotg.corpus.medium.data.Product
import org.appdevforall.cotg.corpus.medium.data.ProductCatalog
import org.appdevforall.cotg.corpus.medium.formatters.TextTruncator

class DetailsActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val catalog =
			ProductCatalog().apply {
				add(Product(sku = "SKU-1", label = "Widget", priceCents = 500))
				add(Product(sku = "SKU-2", label = "Gadget", priceCents = 1200))
			}

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(32, 64, 32, 32)
			}

		val truncator = TextTruncator()
		for (product in catalog.all()) {
			val label = truncator.truncate(product.label, 10)
			val view = TextView(this).apply { text = getString(R.string.product_line_label, label) }
			root.addView(view)
		}

		setContentView(root)
	}
}
