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

/**
 * Where a process-wide caller finds the live metrics watchers (ADFA-5526).
 *
 * The watchers belong to an activity-scoped ViewModel, which is right for the carousel and no use to
 * a crash handler: a crash arrives on any thread, from anywhere, with no activity in hand. This is
 * the one indirection that lets the handler reach them.
 *
 * Deliberately thin, and deliberately nullable. There is no source before the editor has run -- a
 * crash during onboarding, in the project chooser, or in direct boot has no history to report -- and
 * a caller that cannot find one attaches nothing rather than inventing something.
 */
object MetricsSource {
	/** What a crash handler needs to build a snapshot. */
	interface Metrics {
		val memoryUsageWatcher: MemoryUsageWatcher
		val networkUsageWatcher: NetworkUsageWatcher
		val powerUsageWatcher: PowerUsageWatcher
		val annotations: MetricsAnnotationStore
	}

	@Volatile
	var current: Metrics? = null
		private set

	fun register(metrics: Metrics) {
		current = metrics
	}

	/**
	 * Clears [current] if [metrics] is still the registered one.
	 *
	 * Conditional because an activity recreation can register the replacement before the outgoing
	 * one is cleared, and an unconditional clear would then drop the live source.
	 */
	fun unregister(metrics: Metrics) {
		if (current === metrics) {
			current = null
		}
	}
}
