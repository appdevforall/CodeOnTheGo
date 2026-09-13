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
 * Tracks the last value handed to [consume], so a caller can tell "already handled" from "new"
 * without an Activity `savedInstanceState` check. Meant to live as a field on a `ViewModel`: it
 * survives a configuration change (same instance, so a repeat [consume] of the same value is a
 * no-op), but resets after process death (a fresh instance is created), so a process-death
 * recreation still processes a restored pending value instead of silently dropping it.
 *
 * Not thread-safe: [lastHandled] is unsynchronized, so call [consume] from a single thread only
 * (e.g. always from the main thread, as every current call site does).
 */
class LastValueGate<T> {
	private var lastHandled: T? = null

	/** Returns true the first time [value] is passed, or if it differs from the last one seen. */
	fun consume(value: T): Boolean {
		if (lastHandled == value) return false
		lastHandled = value
		return true
	}
}
