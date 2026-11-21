package com.itsaky.androidide.plugins.services

import android.view.View

/**
 * Service that allows plugins to show tooltips using the IDE's tooltip system.
 */
interface IdeTooltipService {
	/**
	 * Show a tooltip from the IDE's own documentation or from this plugin's.
	 *
	 * A [category] of `ide`, `java`, `kotlin` or `xml` reads the IDE's documentation, for example
	 * `showTooltip(view, "java", "android.widget.TextView")`. Any other value reads this plugin's
	 * entries, as [showTooltip] without a category does.
	 *
	 * @param anchorView The view to anchor the tooltip to
	 * @param category The tooltip category
	 * @param tag The tooltip tag
	 */
	fun showTooltip(
		anchorView: View,
		category: String,
		tag: String,
	)

	/**
	 * Show a tooltip from this plugin's own documentation entries.
	 *
	 * @param anchorView The view to anchor the tooltip to
	 * @param tag The tooltip tag
	 */
	fun showTooltip(
		anchorView: View,
		tag: String,
	)
}
