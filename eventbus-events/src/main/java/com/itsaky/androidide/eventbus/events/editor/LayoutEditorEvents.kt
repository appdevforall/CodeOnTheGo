package com.itsaky.androidide.eventbus.events.editor

/**
 * Event to report *caught* (non-fatal) exceptions via EventBus,
 * so that the :app module forwards them to Sentry without coupling here.
 */
data class ReportCaughtExceptionEvent(
    val throwable: Throwable,
    val message: String? = null,
    val extras: Map<String, String> = emptyMap()
)