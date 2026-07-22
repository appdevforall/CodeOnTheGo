package com.itsaky.androidide.viewmodel

import ch.qos.logback.classic.Level
import com.itsaky.androidide.logging.GlobalBufferAppender
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.ILogger

/**
 * Consumes IDE logs from [GlobalBufferAppender]. The ViewModel (not the fragment) is the
 * consumer so that the appender's buffer replay happens exactly once per ViewModel lifetime --
 * registering on every fragment view recreation would re-submit the replayed lines into the
 * retained log history and duplicate them.
 *
 * @author Akash Yadav
 */
class IDELogsViewModel :
	LogViewModel(),
	GlobalBufferAppender.Consumer {
	override val logLevel: Level
		get() = if (FeatureFlags.isDebugLoggingEnabled) Level.DEBUG else Level.INFO

	init {
		GlobalBufferAppender.registerConsumer(this)
	}

	override fun consume(
		level: Level,
		message: String,
	) = submit(level.toILoggerLevel(), message)

	override fun onCleared() {
		GlobalBufferAppender.unregisterConsumer(this)
		super.onCleared()
	}
}

private fun Level.toILoggerLevel(): ILogger.Level =
	when {
		levelInt <= Level.TRACE_INT -> ILogger.Level.VERBOSE
		levelInt <= Level.DEBUG_INT -> ILogger.Level.DEBUG
		levelInt <= Level.INFO_INT -> ILogger.Level.INFO
		levelInt <= Level.WARN_INT -> ILogger.Level.WARNING
		else -> ILogger.Level.ERROR
	}
