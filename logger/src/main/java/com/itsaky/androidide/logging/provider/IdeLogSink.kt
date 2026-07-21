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

	private val isJvm: Boolean by lazy { LogUtils.isJvm() }
	private val externalSinks = CopyOnWriteArrayList<ExternalSink>()

	fun addSink(sink: ExternalSink) {
		externalSinks.add(sink)
	}

	fun removeSink(sink: ExternalSink) {
		externalSinks.remove(sink)
	}

	fun dispatch(level: Level, loggerName: String, message: String, throwable: Throwable?) {
		val fullMessage = if (throwable == null) message else "$message\n${throwable.stackTraceToString()}"
		val formatted = IdeLogFormatter.format(level, loggerName, fullMessage)

		if (isJvm) {
			System.err.print(formatted)
		} else {
			logToLogcat(level, loggerName, fullMessage)
		}

		IdeGlobalLogBuffer.append(level, formatted)

		externalSinks.forEach { sink -> runCatching { sink.onLog(level, loggerName, message, throwable) } }
	}

	private fun logToLogcat(level: Level, loggerName: String, message: String) {
		val tag = IdeLogFormatter.abbreviateLoggerName(loggerName)
		when (level) {
			Level.ERROR -> android.util.Log.e(tag, message)
			Level.WARN -> android.util.Log.w(tag, message)
			Level.INFO -> android.util.Log.i(tag, message)
			Level.DEBUG -> android.util.Log.d(tag, message)
			Level.TRACE -> android.util.Log.v(tag, message)
		}
	}
}
