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
import androidx.annotation.StringRes
import com.itsaky.androidide.resources.R.string

/**
 * Records significant events for the metrics charts to annotate (ADFA-5486).
 *
 * Significant means Gradle task starts and stops, and a build's own start and outcome
 * (ADFA-5509). A real build emits far too many task events to draw -- dozens a second during
 * configuration -- so those are throttled to at most one every [THROTTLE_INTERVAL_MS]. The first
 * event in a quiet period is the one kept, since the interesting moment is when work *began*, not
 * an arbitrary one from the middle of a burst. Build outcomes are never throttled and are the last
 * thing evicted; see [Kind.isThrottled] and [record].
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
	 * What kind of event an annotation marks, which decides both how it is drawn and whether the
	 * throttle applies to it (ADFA-5509).
	 */
	enum class Kind(
		/**
		 * The label for this kind, or `null` for [TASK], whose label is the Gradle task's own name.
		 *
		 * A resource id rather than resolved text: the store lives in a ViewModel that outlives an
		 * activity, so a label resolved at record time would keep the old language after the system
		 * locale changes. Holding the id also removes the only reason a caller had to know which
		 * string went with which kind.
		 */
		@StringRes val labelRes: Int?,
	) {
		/** A Gradle task starting or finishing. Throttled: Gradle emits dozens a second. */
		TASK(labelRes = null),

		/** A build beginning. */
		BUILD_STARTED(string.metrics_annotation_build_started),

		/** A build completing successfully. */
		BUILD_FINISHED(string.metrics_annotation_build_finished),

		/** A build failing. */
		BUILD_FAILED(string.metrics_annotation_build_failed),

		/**
		 * A build stopped by the user. Not a failure: the platform reports a cancel through the
		 * same failure callback, and painting a deliberate stop in the error colour misreports it.
		 */
		BUILD_CANCELLED(string.metrics_annotation_build_cancelled),
		;

		/**
		 * Whether the throttle may drop this kind.
		 *
		 * Only task events. A build outcome dropped because a task marker happened to land two
		 * seconds earlier would be the one annotation on the chart worth having.
		 */
		val isThrottled: Boolean
			get() = this == TASK
	}

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
		/** Decides the marker's colour, and whether the throttle could have dropped it. */
		val kind: Kind = Kind.TASK,
	)

	/**
	 * Records a build outcome. Its label comes from [Kind.labelRes], so the caller names the
	 * outcome and nothing else.
	 */
	@Synchronized
	fun recordBuild(kind: Kind): Boolean = record(label = "", kind = kind)

	/**
	 * Records [label] unless another annotation was recorded within [THROTTLE_INTERVAL_MS].
	 *
	 * The throttle only applies to [Kind.TASK]; a build outcome is always kept. See
	 * [Kind.isThrottled].
	 *
	 * @return whether it was recorded.
	 */
	@Synchronized
	fun record(
		label: String,
		kind: Kind = Kind.TASK,
	): Boolean {
		val now = nowMillis()
		val since = lastRecordedAt
		if (kind.isThrottled && since != null && now - since < THROTTLE_INTERVAL_MS) {
			return false
		}

		// Set even for an unthrottled kind, so the next task marker waits its interval instead of
		// landing a few pixels from a build marker and colliding with it.
		lastRecordedAt = now
		annotations.addLast(Annotation(now, label, nextSequence++, kind))
		evictToCapacity()
		return true
	}

	/**
	 * Drops the oldest annotations until the store is back within [MAX_ANNOTATIONS].
	 *
	 * Task markers go first, whatever their age. Plain oldest-first eviction dropped a build's
	 * "Build started" while the build was still running -- 256 markers at one per
	 * [THROTTLE_INTERVAL_MS] is about twenty minutes, which a clean build on a phone can exceed --
	 * leaving an unpaired outcome on the chart and no way to see how long the build took. Task
	 * markers are the padding here; the build's own moments are the point.
	 */
	private fun evictToCapacity() {
		while (annotations.size > MAX_ANNOTATIONS) {
			val oldestTask = annotations.indexOfFirst { it.kind.isThrottled }
			if (oldestTask >= 0) {
				annotations.removeAt(oldestTask)
			} else {
				// Nothing but build outcomes left, so the oldest of those has to go.
				annotations.removeFirst()
			}
		}
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
