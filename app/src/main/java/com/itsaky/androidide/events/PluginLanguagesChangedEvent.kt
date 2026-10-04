package com.itsaky.androidide.events

import com.itsaky.androidide.eventbus.events.Event

data class PluginLanguagesChangedEvent(
	val fileTypes: Set<String>,
) : Event()
