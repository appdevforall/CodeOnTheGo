package org.appdevforall.cotg.corpus.mixedcyclic.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.appdevforall.cotg.corpus.mixedcyclic.R
import org.appdevforall.cotg.corpus.mixedcyclic.core.TreeNode

class MainActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(32, 64, 32, 32)
			}

		root.addView(TextView(this).apply { text = getString(R.string.tree_label) })
		root.addView(TextView(this).apply { text = TreeNode("root").summary() })
		root.addView(TextView(this).apply { text = TreeNode("root").describe() })
		root.addView(TextView(this).apply { text = TreeNode.leaf("first").describe() })

		setContentView(root)
	}
}
