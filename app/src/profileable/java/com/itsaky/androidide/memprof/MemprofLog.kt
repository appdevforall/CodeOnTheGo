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

/**
 * Writes memprof lines straight to logcat under [tag], bypassing slf4j.
 *
 * The IDE's slf4j provider also hands every line to the IDE Logs pane. The allocation sampler alone
 * writes a line every 250 ms, so routed through slf4j the measurement lines flood the pane the user
 * reads, and the pane's buffering adds heap and CPU work to the very process being measured. The
 * harness scripts read logcat, which is the only place these lines are meant for.
 *
 * Messages use slf4j's `{}` placeholders, and a trailing [Throwable] argument is printed as a stack
 * trace, so call sites read the same as the slf4j calls they replaced.
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
		val throwable = args.lastOrNull() as? Throwable
		val values = if (throwable == null) args.asList() else args.asList().dropLast(1)
		val message = format(format, values)
		Log.println(priority, tag, if (throwable == null) message else message + '\n' + Log.getStackTraceString(throwable))
	}

	companion object {
		/** Replaces each `{}` in [format] with the next of [values], in order; extra values are ignored. */
		internal fun format(
			format: String,
			values: List<Any?>,
		): String {
			if (values.isEmpty()) return format
			val out = StringBuilder(format.length + 16 * values.size)
			var next = 0
			var start = 0
			while (true) {
				val at = format.indexOf("{}", start)
				if (at < 0 || next == values.size) break
				out.append(format, start, at).append(values[next++])
				start = at + 2
			}
			return out.append(format, start, format.length).toString()
		}
	}
}
