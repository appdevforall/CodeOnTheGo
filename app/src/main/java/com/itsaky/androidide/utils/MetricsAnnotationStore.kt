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

	/** Hands each annotation its [Annotation.sequence]. */
	private var nextSequence: Long = 0L

	/**
	 * An annotated moment.
	 *
	 * @property atMillis When it happened, on the same clock as [nowMillis].
	 * @property label What to show against it.
	 */
	data class Annotation(
		val atMillis: Long,
		val label: String,
		/**
		 * Position in the order recorded, counted from the first annotation of the session.
		 *
		 * The chart staggers labels across rows to stop them overwriting each other, and picks the
		 * row from this. Its own position in [recentAnnotations] would not do: that list shifts as
		 * older entries age out of it, so a label would hop between rows while merely sitting
		 * still. Counting from the first annotation instead pins a label to one row for life, and
		 * makes consecutive annotations differ, which is when a collision is likeliest.
		 */
		val sequence: Long,
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
		annotations.addLast(Annotation(now, label, nextSequence++))
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
		nextSequence = 0L
	}

	companion object {
		/**
		 * Gradle emits task events far faster than a chart can show them; one every five seconds is
		 * what the ticket asks for.
		 */
		const val THROTTLE_INTERVAL_MS = 5_000L

		/**
		 * Enough to cover the whole visible window at the slowest sampling rate.
		 *
		 * Derived rather than picked. The renderer asks for the annotations within
		 * `(VISIBLE_SAMPLES + 1) * interval`, which at [MetricsSamplingRates.MAX_INTERVAL_MS] is
		 * just over an hour, and the throttle admits one task marker every
		 * [THROTTLE_INTERVAL_MS] -- so a busy hour can fill the window with more markers than a
		 * flat 256 could hold, and eviction then dropped markers that still had samples on
		 * screen beside them. The bound still exists: a session cannot grow this without limit,
		 * it just no longer cuts into what is being drawn.
		 */
		val MAX_ANNOTATIONS =
			(VISIBLE_WINDOW_SAMPLES * MetricsSamplingRates.MAX_INTERVAL_MS / THROTTLE_INTERVAL_MS).toInt()

		/**
		 * How many samples a chart shows at once, plus the one the renderer allows for.
		 *
		 * Held here rather than read from MetricsChartRenderer.VISIBLE_SAMPLES: this class is in
		 * `utils` and the renderer is in `ui`, so reaching for it would be an upward dependency.
		 * If the renderer's window changes, this follows.
		 */
		private const val VISIBLE_WINDOW_SAMPLES = 61L
	}
}
