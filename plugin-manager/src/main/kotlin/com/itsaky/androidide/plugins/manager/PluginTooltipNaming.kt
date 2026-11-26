package com.itsaky.androidide.plugins.manager

import com.itsaky.androidide.idetooltips.TooltipCategory

const val PLUGIN_CATEGORY_PREFIX: String = "plugin_"

private val IDE_TOOLTIP_CATEGORIES =
	setOf(
		TooltipCategory.CATEGORY_IDE,
		TooltipCategory.CATEGORY_JAVA,
		TooltipCategory.CATEGORY_KOTLIN,
		TooltipCategory.CATEGORY_XML,
	)

fun pluginCategory(pluginId: String): String = "$PLUGIN_CATEGORY_PREFIX$pluginId"

internal fun pluginReadableCategory(
	pluginId: String,
	category: String,
): String = if (category in IDE_TOOLTIP_CATEGORIES) category else pluginCategory(pluginId)

fun pluginTooltipTag(
	pluginId: String,
	itemId: String,
): String = "$pluginId.$itemId"
