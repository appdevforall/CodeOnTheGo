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

package com.itsaky.androidide.utils

import android.os.SystemClock

/**
 * Records significant events for the metrics charts to annotate (ADFA-5486).
 *
 * Significant means Gradle task starts and stops. A real build emits far too many of those to draw
 * -- dozens a second during configuration -- so they are throttled to at most one every
 * [THROTTLE_INTERVAL_MS]. The first event in a quiet period is the one kept, since the interesting
 * moment is when work *began*, not an arbitrary one from the middle of a burst.
 *
 * Annotations are stored by wall-clock time rather than by sample position, because the charts hold
 * a ring buffer whose contents shift under them; a stored index would drift. The renderer converts
 * a timestamp to an x position from its age, and anything older than the buffer falls off.
 */
class MetricsAnnotationStore(
	private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
	private val annotations = ArrayDeque<Annotation>()

	/**
	 * When the last annotation was recorded, or `null` if none has been. Nullable rather than a
	 * sentinel: `now - Long.MIN_VALUE` overflows to a negative gap, which reads as "inside the
	 * throttle window" and silently swallows every annotation for the life of the store.
	 */
	private var lastRecordedAt: Long? = null

	/**
	 * An annotated moment.
	 *
	 * @property atMillis When it happened, on the same clock as [nowMillis].
	 * @property label What to show against it.
	 */
	data class Annotation(
		val atMillis: Long,
		val label: String,
	)

	/**
	 * Records [label] unless another annotation was recorded within [THROTTLE_INTERVAL_MS].
	 *
	 * @return whether it was recorded.
	 */
	@Synchronized
	fun record(label: String): Boolean {
		val now = nowMillis()
		val since = lastRecordedAt
		if (since != null && now - since < THROTTLE_INTERVAL_MS) {
			return false
		}

		lastRecordedAt = now
		annotations.addLast(Annotation(now, label))
		while (annotations.size > MAX_ANNOTATIONS) {
			annotations.removeFirst()
		}
		return true
	}

	/**
	 * The annotations recorded within [withinMillis] of now, oldest first.
	 */
	@Synchronized
	fun recentAnnotations(withinMillis: Long): List<Annotation> {
		val cutoff = nowMillis() - withinMillis
		return annotations.filter { it.atMillis >= cutoff }
	}

	@Synchronized
	fun clear() {
		annotations.clear()
		lastRecordedAt = null
	}

	companion object {
		/**
		 * Gradle emits task events far faster than a chart can show them; one every five seconds is
		 * what the ticket asks for.
		 */
		const val THROTTLE_INTERVAL_MS = 5_000L

		/**
		 * Enough to cover the deepest buffer at the slowest sampling rate, bounded so a long
		 * session cannot grow this without limit.
		 */
		const val MAX_ANNOTATIONS = 256
	}
}
