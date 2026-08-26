package com.itsaky.androidide.api

import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.ui.EditorBottomSheet
import com.itsaky.androidide.viewmodel.BuildOutputViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference

/**
 * Provides access to the EditorBottomSheet instance from a decoupled context.
 * This acts as a service locator to avoid memory leaks.
 */
object BuildOutputProvider {
	// Both fields are written from the main thread (or a test) and read from the background thread
	// a plugin calls in on, so the reader needs the write to be visible.
	@Volatile
	private var bottomSheetRef: WeakReference<EditorBottomSheet>? = null

	/** Session-file directory override for unit tests, which have no [IDEApplication]. */
	@Volatile
	private var sessionDirOverride: File? = null

	fun setBottomSheet(sheet: EditorBottomSheet) {
		this.bottomSheetRef = WeakReference(sheet)
	}

	fun clearBottomSheet() {
		this.bottomSheetRef?.clear()
		this.bottomSheetRef = null
	}

	/**
	 * Returns the build output for consumers outside the editor UI (the AI plugins'
	 * `read_build_output`), or `null` when there is none. Never returns a status message: the caller
	 * cannot tell one from a log, and a plausible non-empty answer is worse than nothing.
	 *
	 * Reads the live bottom-sheet content first, then the session file on disk. The fallback must
	 * trigger on blank, not just null: [com.itsaky.androidide.fragments.output.BuildOutputFragment.getShareableContent]
	 * returns `""` while detached, and the file is still there after a crashed build -- exactly when
	 * the log matters most.
	 *
	 * Line timing prefixes are stripped; ~22 characters a line of clock time no agent can use.
	 * Does disk I/O, so call off the main thread.
	 */
	fun getBuildOutputContent(): String? {
		val content = liveContent() ?: sessionFileTail() ?: return null
		return BuildOutputViewModel
			.filterLines(
				content = BuildOutputViewModel.tailFromLineStart(content, WINDOW_MAX_CHARS),
				query = "",
				showTimestamps = false,
				showDeltas = false,
			).takeIf { it.isNotBlank() }
	}

	/** Sets the directory holding the session file. Test seam for the [sessionFileTail] fallback. */
	@VisibleForTesting
	internal fun setSessionDirectoryForTest(dir: File?) {
		sessionDirOverride = dir
	}

	/**
	 * The bottom sheet's own view of the output, read on the main thread.
	 *
	 * The fragment's lifecycle state and its lazily resolved view model are main-thread-only, and a
	 * plugin calls in from a background thread, so the read is dispatched there and bounded by
	 * [LIVE_READ_TIMEOUT_MS]. Exceeding that is not an error: the caller falls through to the
	 * session file, which holds the same log one flush behind.
	 */
	private fun liveContent(): String? =
		runCatching {
			if (isMainThread()) {
				sheetContent()
			} else {
				runBlocking {
					withTimeoutOrNull(LIVE_READ_TIMEOUT_MS) {
						withContext(Dispatchers.Main) { sheetContent() }
					}
				}
			}
		}.getOrNull()?.takeIf { it.isNotBlank() }

	/**
	 * Whether the caller is already on the main thread. Also true under a JVM unit test, where
	 * [Looper] is not mocked and throws: there is no main thread to dispatch to, and the read is
	 * safe on the test thread.
	 */
	private fun isMainThread(): Boolean = runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(true)

	/** Main thread only; see [liveContent]. */
	private fun sheetContent(): String? =
		bottomSheetRef
			?.get()
			?.pagerAdapter
			?.buildOutputFragment
			?.getShareableContent()

	private fun sessionFileTail(): String? {
		val dir = sessionDirOverride ?: runCatching { IDEApplication.instance.cacheDir }.getOrNull() ?: return null
		// Read without BuildOutputViewModel's lock: a concurrent append can leave the window starting
		// mid-UTF-8-sequence, which decodes to a single U+FFFD rather than throwing.
		return BuildOutputViewModel
			.readTailFromFile(File(dir, BuildOutputViewModel.SESSION_FILE_NAME), WINDOW_MAX_CHARS)
			.takeIf { it.isNotBlank() }
	}

	/**
	 * Upper bound on the returned window. Consumers window this down further (the agent tool takes
	 * 8000 characters); this only keeps a whole session out of a single string.
	 */
	@VisibleForTesting
	internal const val WINDOW_MAX_CHARS = 128 * 1024

	/** How long the live read may hold the calling thread before the session file takes over. */
	private const val LIVE_READ_TIMEOUT_MS = 500L
}
