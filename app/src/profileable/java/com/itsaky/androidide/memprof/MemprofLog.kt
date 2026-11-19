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

package com.itsaky.androidide.memprof

import android.util.Log
import org.slf4j.helpers.MessageFormatter

/**
 * Writes memprof lines straight to logcat under [tag], bypassing the slf4j logger.
 *
 * The IDE's slf4j provider also hands every line to the IDE Logs pane. The allocation sampler alone
 * writes a line every 250 ms, so routed through slf4j the measurement lines flood the pane the user
 * reads, and the pane's buffering adds heap and CPU work to the very process being measured. The
 * harness scripts read logcat, which is the only place these lines are meant for.
 *
 * Messages are formatted by slf4j's [MessageFormatter], placeholders and trailing [Throwable] alike,
 * so call sites read the same as the slf4j calls they replaced.
 */
class MemprofLog(
	private val tag: String,
) {
	/** Logs at debug priority. */
	fun d(
		format: String,
		vararg args: Any?,
	) = write(Log.DEBUG, format, args)

	/** Logs at info priority. */
	fun i(
		format: String,
		vararg args: Any?,
	) = write(Log.INFO, format, args)

	/** Logs at warn priority. */
	fun w(
		format: String,
		vararg args: Any?,
	) = write(Log.WARN, format, args)

	/** Logs at error priority. */
	fun e(
		format: String,
		vararg args: Any?,
	) = write(Log.ERROR, format, args)

	private fun write(
		priority: Int,
		format: String,
		args: Array<out Any?>,
	) {
		val formatted = MessageFormatter.arrayFormat(format, args)
		val throwable = formatted.throwable
		val message = formatted.message
		Log.println(priority, tag, if (throwable == null) message else message + '\n' + Log.getStackTraceString(throwable))
	}
}
