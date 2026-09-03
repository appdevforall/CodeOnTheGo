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

package com.itsaky.androidide.deeplink

import com.itsaky.androidide.models.DeepLinkOpenRequest

/**
 * In-memory, process-lifetime handoff for "the user confirmed closing the current project via a
 * deep link; once this activity instance is actually destroyed, open the requested project."
 *
 * Deliberately not acted on synchronously inside the close-confirmation dialog's button callback --
 * see [com.itsaky.androidide.activities.editor.EditorHandlerActivity.onDestroy] for why the hand-off
 * must wait until the old, `singleTask` activity instance is guaranteed torn down.
 *
 * Koin-provided (`single` in `di/AppModule.kt`) rather than a Kotlin `object`, per ADR 0006 --
 * still one process-wide instance either way, but this keeps it substitutable in tests and out of
 * the "hand-rolled singleton" pattern the ADR asks new code to avoid.
 */
internal class PendingDeepLinkOpen {
	@Volatile
	private var request: DeepLinkOpenRequest? = null

	/**
	 * The activity instance that armed [request], compared by identity only and never dereferenced,
	 * so holding it here cannot keep a destroyed activity alive in any way that matters -- the
	 * reference is dropped the moment the hand-off is drained or superseded.
	 */
	@Volatile
	private var owner: Any? = null

	/** Records the hand-off [owner] confirmed, replacing any earlier one. */
	fun arm(
		owner: Any,
		request: DeepLinkOpenRequest,
	) {
		this.owner = owner
		this.request = request
	}

	/**
	 * Hands back and clears the request [owner] armed, or null if it armed none -- so an instance
	 * only ever performs its own hand-off.
	 *
	 * The ownership test replaces the `isFinishing && didCompleteLiveOnCreate` pair this used to be
	 * gated on, which asked two different questions and got both wrong at the edges. isFinishing was
	 * false whenever the deferred `finish()` never ran (its `lifecycleScope` coroutine cancelled at
	 * ON_DESTROY, or a config-change recreate landing mid-save), so a confirmed switch was stranded
	 * in this process-wide `single` and fired later against an unrelated project close.
	 * didCompleteLiveOnCreate was standing in for "did I arm this?" -- which is now asked directly.
	 */
	fun drainArmedBy(owner: Any): DeepLinkOpenRequest? {
		if (this.owner !== owner) {
			return null
		}
		val drained = request
		request = null
		this.owner = null
		return drained
	}
}
