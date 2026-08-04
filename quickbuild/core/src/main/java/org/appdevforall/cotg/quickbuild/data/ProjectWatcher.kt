package org.appdevforall.cotg.quickbuild.data

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles

/**
 * Watches the open project on-device and reports coalesced batches of changed files.
 *
 * Triggers on file *change* from any source - the CoGo editor, a Termux script, a plugin
 * write, a `git pull` - not on an editor save event, so edits made outside the editor still
 * rebuild. Implementations run in CoGo's process on the phone; a Mac-side poller or an
 * `adb`-driven trigger must never be wired into this path. Relevance filtering and debounce
 * belong to the watcher, concurrency with in-flight builds to the orchestrator.
 */
interface ProjectWatcher {
	/**
	 * Starts watching, invoking [onBatch] once per coalesced burst.
	 *
	 * Modified and created paths arrive in [ChangedFiles.Known.files], deleted ones in
	 * [ChangedFiles.Known.removed], with build intermediates and temp files already filtered
	 * out. Need not be idempotent; the session manager calls it once per live session.
	 */
	fun start(onBatch: (ChangedFiles.Known) -> Unit)

	/** Stop watching and release OS resources. Safe to call when not started. */
	fun stop()
}
