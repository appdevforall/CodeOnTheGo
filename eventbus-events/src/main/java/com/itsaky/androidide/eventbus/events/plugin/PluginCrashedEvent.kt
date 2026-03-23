package com.itsaky.androidide.eventbus.events.plugin

import com.itsaky.androidide.eventbus.events.Event

class PluginCrashedEvent(
    val pluginId: String,
    val pluginName: String,
    val crashCount: Int,
    val wasDisabled: Boolean
) : Event()
