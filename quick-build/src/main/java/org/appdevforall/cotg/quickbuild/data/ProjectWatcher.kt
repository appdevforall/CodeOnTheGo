package org.appdevforall.cotg.quickbuild.data

import java.io.File

/**
 * Watches the open project on-device and reports coalesced batches of changed files, from
 * ANY source - the CoGo editor, a Termux script, a plugin write, a `git pull`. The reload
 * loop triggers on file *change*, not on an editor save event, so edits made outside the
 * editor still rebuild (design-watcher-and-testing.md section 1).
 *
 * On-device only: the implementation runs in CoGo's process on the phone. A Mac-side
 * poller or `adb`-driven trigger would violate the on-device invariant and must never be
 * wired into this path.
 *
 * The watcher applies its own relevance filtering and debounce; a callback receives one
 * set per coalesced burst. Concurrency with in-flight builds is the orchestrator's job -
 * the watcher only reports.
 */
interface ProjectWatcher {
	/**
	 * Start watching. [onBatch] is invoked once per coalesced burst with the relevant
	 * changed files (build intermediates and temp files already filtered out). Idempotent
	 * is not required; the session manager calls [start] once per live session.
	 */
	fun start(onBatch: (Set<File>) -> Unit)

	/** Stop watching and release OS resources. Safe to call when not started. */
	fun stop()
}
