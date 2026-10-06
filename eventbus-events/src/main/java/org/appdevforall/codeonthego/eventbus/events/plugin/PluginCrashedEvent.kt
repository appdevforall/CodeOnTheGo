package org.appdevforall.codeonthego.eventbus.events.plugin

import org.appdevforall.codeonthego.eventbus.events.Event

class PluginCrashedEvent(
    val pluginId: String,
    val pluginName: String,
    val crashCount: Int,
    val wasDisabled: Boolean,
    val stackTrace: String
) : Event()
