package com.itsaky.androidide.activities.editor

/**
 * How one file's save ended.
 *
 * `CodeEditorView.save` reports "nothing to do" and "the write failed" with the same `false`,
 * and the two mean opposite things to a caller that wants to know whether its content is on
 * disk. A save also has an outcome even when the coroutine awaiting it is cancelled before it
 * can return one, so the verdict is recorded rather than only returned.
 */
internal enum class FileSaveOutcome {
	/** The buffer was written to disk on this call. */
	WRITTEN,

	/** Nothing to write: the buffer already matched what is on disk. */
	ALREADY_CLEAN,

	/** No open editor holds the file. */
	NOT_OPEN,

	/** A write was attempted and did not land. */
	FAILED,
	;

	/** Whether the buffer's content is on disk, regardless of which call put it there. */
	val reachedDisk: Boolean
		get() = this == WRITTEN || this == ALREADY_CLEAN
}
