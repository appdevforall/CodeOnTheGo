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

import org.slf4j.event.Level
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Ports the message format previously produced by Logback's `IDELogFormatLayout`
 * (pattern `%d{dd-MM HH:mm:ss.SS} %5level [%thread] %logger{0}: %msg%n`) without
 * depending on Logback's `PatternLayout` engine.
 *
 * @author Akash Yadav
 */
object IdeLogFormatter {

	private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM HH:mm:ss")

	/**
	 * Abbreviates a fully-qualified logger name to its last segment, matching Logback's
	 * `%logger{0}` behavior.
	 */
	fun abbreviateLoggerName(loggerName: String): String {
		val lastDot = loggerName.lastIndexOf('.')
		return if (lastDot == -1) loggerName else loggerName.substring(lastDot + 1)
	}

	fun format(level: Level, loggerName: String, message: String, omitMessage: Boolean = false): String {
		val now = LocalDateTime.now()
		val centiseconds = now.nano / 10_000_000
		val threadName = Thread.currentThread().name
		val tag = abbreviateLoggerName(loggerName)

		val prefix = "${TIMESTAMP_FORMAT.format(now)}.%02d %5s [%s] %s:".format(centiseconds, level.name, threadName, tag)

		return if (omitMessage) "$prefix\n" else "$prefix $message\n"
	}
}
