/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.logging.provider

import com.itsaky.androidide.logging.utils.LogUtils
import org.slf4j.event.Level
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes a formatted log line to the appropriate physical sink: `android.util.Log` when
 * running on Android, `System.err` when running in a plain JVM (e.g. the standalone
 * tooling-api process). Mirrors the existing `LogcatAppender`/`StdErrAppender` split, but
 * without any Logback appender machinery.
 *
 * `android.util.Log` is referenced directly rather than through Timber: this module is a
 * plain `java-library` compiled only against the Android SDK stub jar
 * (`compileOnly(projects.subprojects.frameworkStubs)`), which cannot resolve a real Android
 * AAR dependency like Timber.
 *
 * @author Akash Yadav
 */
object IdeLogRouter {

	/** A side channel for log events, e.g. forwarding over RPC or into crash reporting. */
	fun interface ExternalSink {
		fun onLog(level: Level, loggerName: String, message: String, throwable: Throwable?)
	}

	/**
	 * System property that toggles [System.err] output in a plain JVM process (default `true`).
	 * Used by [com.itsaky.androidide.services.builder.ToolingServerRunner] to pass the app's
	 * debug-logging preference into the standalone tooling-api JVM process it launches.
	 */
	const val PROP_JVM_STDERR_ENABLED = "ide.logging.jvmStdErrAppenderEnabled"

	private val isJvm: Boolean by lazy { LogUtils.isJvm() }
	private val jvmStdErrEnabled: Boolean by lazy { System.getProperty(PROP_JVM_STDERR_ENABLED, "true").toBoolean() }
	private val externalSinks = CopyOnWriteArrayList<ExternalSink>()

	fun addSink(sink: ExternalSink) {
		externalSinks.add(sink)
	}

	fun removeSink(sink: ExternalSink) {
		externalSinks.remove(sink)
	}

	fun dispatch(level: Level, loggerName: String, message: String, throwable: Throwable?) {
		val fullMessage = IdeLogFormatter.appendThrowable(message, throwable)

		// A log call must never throw into the caller: contain failures the way Logback's
		// AppenderBase.doAppend used to, falling back to a raw stderr line so the message
		// isn't lost entirely.
		runCatching {
			val formatted = IdeLogFormatter.format(level, loggerName, fullMessage)

			if (isJvm) {
				if (jvmStdErrEnabled) {
					System.err.print(formatted)
				}
			} else {
				logToLogcat(level, loggerName, fullMessage)
			}

			IdeGlobalLogBuffer.append(level, formatted)
		}.onFailure { error ->
			System.err.println("IdeLogRouter: failed to dispatch log line: $fullMessage ($error)")
		}

		externalSinks.forEach { sink ->
			runCatching { sink.onLog(level, loggerName, message, throwable) }
				.onFailure { error -> System.err.println("IdeLogRouter: sink $sink failed: $error") }
		}
	}

	private fun logToLogcat(level: Level, loggerName: String, message: String) {
		val tag = LogUtils.processLogTag(IdeLogFormatter.abbreviateLoggerName(loggerName))
		when (level) {
			Level.ERROR -> android.util.Log.e(tag, message)
			Level.WARN -> android.util.Log.w(tag, message)
			Level.INFO -> android.util.Log.i(tag, message)
			Level.DEBUG -> android.util.Log.d(tag, message)
			Level.TRACE -> android.util.Log.v(tag, message)
		}
	}
}
