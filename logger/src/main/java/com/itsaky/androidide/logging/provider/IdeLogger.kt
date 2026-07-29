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
 * DEBUG and above are always enabled: the old Logback setup never called `.setLevel()`
 * anywhere in this codebase, leaving the root logger at Logback's default of DEBUG. TRACE
 * is disabled to match that same default (Logback's root logger is never TRACE unless
 * explicitly configured, which this codebase never did). The only other level filtering
 * (`IDELogFragment`'s in-app log tab) happens downstream, per-consumer, in [IdeGlobalLogBuffer].
 *
 * @author Akash Yadav
 */
class IdeLogger(
	private val loggerName: String,
) : LegacyAbstractLogger() {
	override fun getName(): String = loggerName

	override fun isTraceEnabled(): Boolean = false

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
