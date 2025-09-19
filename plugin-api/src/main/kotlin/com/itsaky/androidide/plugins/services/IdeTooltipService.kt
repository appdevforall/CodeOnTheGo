package com.itsaky.androidide.plugins.services

import android.view.View

/**
 * Service that allows plugins to show tooltips using the IDE's tooltip system.
 */
interface IdeTooltipService {

    /**
     * Show a tooltip for the given category and tag.
     *
     * @param anchorView The view to anchor the tooltip to
     * @param category The tooltip category
     * @param tag The tooltip tag
     */
    fun showTooltip(anchorView: View, category: String, tag: String)

    /**
     * Show a tooltip for the default IDE category.
     *
     * @param anchorView The view to anchor the tooltip to
     * @param tag The tooltip tag
     */
    fun showTooltip(anchorView: View, tag: String)

    /**
     * Check if a tooltip exists for the given category and tag.
     *
     * @param category The tooltip category
     * @param tag The tooltip tag
     * @return True if the tooltip exists
     */
    fun hasTooltip(category: String, tag: String): Boolean
}