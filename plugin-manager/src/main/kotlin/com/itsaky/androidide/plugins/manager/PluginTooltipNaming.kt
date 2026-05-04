package com.itsaky.androidide.plugins.manager

const val PLUGIN_CATEGORY_PREFIX: String = "plugin_"

fun pluginCategory(pluginId: String): String = "$PLUGIN_CATEGORY_PREFIX$pluginId"

fun pluginTooltipTag(pluginId: String, itemId: String): String = "$pluginId.$itemId"
