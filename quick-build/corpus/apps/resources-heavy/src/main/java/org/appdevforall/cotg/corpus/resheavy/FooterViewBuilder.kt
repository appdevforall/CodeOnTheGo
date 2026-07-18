package org.appdevforall.cotg.corpus.resheavy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/** Inflates the shared footer layout — its own res inflation, separate from the activity's own. */
class FooterViewBuilder {
	fun build(
		inflater: LayoutInflater,
		parent: ViewGroup,
	): View = inflater.inflate(R.layout.view_footer, parent, false)
}
