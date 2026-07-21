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

package com.itsaky.androidide.tooling.impl.logging

import com.itsaky.androidide.logging.provider.IdeLogFormatter
import com.itsaky.androidide.logging.provider.IdeLogRouter
import com.itsaky.androidide.tooling.api.messages.LogMessageParams
import com.itsaky.androidide.tooling.impl.Main
import org.slf4j.event.Level

/**
 * [IdeLogRouter.ExternalSink] which forwards all logs to the tooling API client.
 *
 * @author Akash Yadav
 */
object ToolingApiAppender : IdeLogRouter.ExternalSink {
	override fun onLog(
		level: Level,
		loggerName: String,
		message: String,
		throwable: Throwable?,
	) {
		val fullMessage = if (throwable == null) message else "$message\n${throwable.stackTraceToString()}"
		val formatted = IdeLogFormatter.format(level, loggerName, fullMessage)
		Main.client?.logMessage(LogMessageParams(level.name.first(), loggerName, formatted))
	}
}
