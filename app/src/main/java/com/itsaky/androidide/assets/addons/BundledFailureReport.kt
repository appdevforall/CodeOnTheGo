package com.itsaky.androidide.assets.addons

import com.itsaky.androidide.utils.TrackedFlashbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Shows the failures the user has not been told about with [show], and marks them reported only once
 * the user dismisses the bar. Until then this suspends, so a caller that runs one report at a time
 * shows no second bar while one is up.
 *
 * If the caller's scope ends first - the screen went away, say recreated by a font scale change - the
 * failures stay unreported and the next report shows them again. Destroying an activity fires no
 * dismiss callback, so only the user ends the wait. A failure recorded while the bar is up is not
 * marked: it was not on the bar.
 */
suspend fun BundledAddonsRecord.reportFailures(
	io: CoroutineContext = Dispatchers.IO,
	show: (List<BundledPluginFailure>) -> TrackedFlashbar?,
) {
	val failures = withContext(io) { runCatching { unreportedFailures() }.getOrDefault(emptyList()) }
	if (failures.isEmpty()) return
	val bar = show(failures) ?: return
	bar.awaitDismissed()
	// The user dismissed the bar: record that even if the screen goes away now.
	withContext(NonCancellable + io) { runCatching { markReported(failures) } }
}
