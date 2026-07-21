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

import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter

/**
 * SLF4J [org.slf4j.Logger] implementation backed by [IdeLogRouter] instead of Logback.
 *
 * No level filtering is applied: the current Logback setup never calls `.setLevel()`
 * anywhere in this codebase, so every level is enabled here too. The only existing
 * level filtering (`IDELogFragment`'s in-app log tab) happens downstream, per-consumer,
 * in [IdeGlobalLogBuffer].
 *
 * @author Akash Yadav
 */
class IdeLogger(
	private val loggerName: String,
) : LegacyAbstractLogger() {
	override fun getName(): String = loggerName

	override fun isTraceEnabled(): Boolean = true

	override fun isDebugEnabled(): Boolean = true

	override fun isInfoEnabled(): Boolean = true

	override fun isWarnEnabled(): Boolean = true

	override fun isErrorEnabled(): Boolean = true

	override fun getFullyQualifiedCallerName(): String = IdeLogger::class.java.name

	override fun handleNormalizedLoggingCall(
		level: Level,
		marker: Marker?,
		messagePattern: String,
		arguments: Array<Any?>?,
		throwable: Throwable?,
	) {
		val message =
			if (arguments.isNullOrEmpty()) {
				messagePattern
			} else {
				MessageFormatter.arrayFormat(messagePattern, arguments).message
			}

		IdeLogRouter.dispatch(level, loggerName, message, throwable)
	}
}
