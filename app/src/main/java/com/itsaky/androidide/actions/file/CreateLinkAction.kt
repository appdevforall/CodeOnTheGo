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

package com.itsaky.androidide.actions.file

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.activities.projectsRoot
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.utils.copyToClipboard
import com.itsaky.androidide.utils.deepLinkTargetOfOpenProjectOrNull
import com.itsaky.androidide.utils.deepLinkTargetOfOpenProjectWithoutIo
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Copies a deep link to the current file -- and to the cursor's line and column within it -- to the
 * clipboard, so it can be pasted somewhere another person (or the same person on another device) can
 * tap it. The read side of the same link is
 * [DeepLinkActivity][com.itsaky.androidide.activities.DeepLinkActivity].
 *
 * @author David Schachter
 */
class CreateLinkAction(
	context: Context,
	override val order: Int,
) : FileTabAction() {
	override val id: String = ID

	companion object {
		const val ID = "ide.editor.fileTab.createLink"

		private val log = LoggerFactory.getLogger(CreateLinkAction::class.java)

		/**
		 * Verdicts for "can a link name this project", keyed by project path. Keyed rather than a
		 * single slot so a slow answer for a project the user has left cannot be mistaken for the
		 * current one -- there is nothing to invalidate and no stale-write window to guard. Holds only
		 * strings and booleans, so it is safe in a companion.
		 */
		private val linkableProjects = ConcurrentHashMap<String, Boolean>()

		/** Paths whose canonicalisation is already running, so N menu opens launch one job, not N. */
		private val canonicalisationsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

		/**
		 * Whether a link can name the open project, or `null` while that is still being decided off
		 * this thread.
		 *
		 * `prepare()` has to answer synchronously, and the full rule canonicalises both sides --
		 * filesystem work that REVIEW.md section 3 and ADR 0007 require be moved rather than
		 * suppressed, since the StrictMode whitelist is for vendored code we cannot change and never
		 * for our own. So [deepLinkTargetOfOpenProjectWithoutIo] settles the common case with no disk
		 * call, and only the residue -- paths differing as text, where a symlink might still make them
		 * one directory -- goes to [Dispatchers.IO].
		 *
		 * While that rare case is pending the item is absent and reappears on the next menu open.
		 * Showing it optimistically would mean a tap that fails, which reads worse.
		 */
		private fun linkableProject(
			activity: EditorHandlerActivity,
			projectPath: String,
		): Boolean? {
			linkableProjects[projectPath]?.let { return it }

			val root = runCatching { projectsRoot() }.getOrNull() ?: return null
			val projectName = File(projectPath).name

			deepLinkTargetOfOpenProjectWithoutIo(projectPath, projectName, root)?.let {
				linkableProjects[projectPath] = it
				return it
			}

			// add() is the atomic claim: @Volatile would give visibility without atomicity, so a
			// check-then-set here could still let two menu opens launch the same canonicalisation.
			if (!canonicalisationsInFlight.add(projectPath)) return null

			val job =
				activity.lifecycleScope.launch(Dispatchers.IO) {
					// Cached only when the filesystem actually answered. deepLinkTargetOfOpenProjectOrNull
					// returns null for "could not tell", which a momentary EACCES or EIO produces -- and
					// caching that as "not linkable" would hide the item for the rest of the process over
					// a condition that has since cleared.
					val verdict =
						runCatching { deepLinkTargetOfOpenProjectOrNull(projectPath, projectName, root) }
							// A backstop, not the retry path: that function reports failure by returning
							// null rather than throwing. Present so an unforeseen throw cannot reach the
							// crash wrapper from a launch with no handler (REVIEW.md section 1).
							.onFailure { log.warn("Could not determine whether the open project can be linked", it) }
							.getOrNull()

					if (verdict != null) {
						linkableProjects[projectPath] = verdict
					}
				}

			// Released here and nowhere else. invokeOnCompletion fires for both outcomes that matter --
			// the block ran, and the block never ran because lifecycleScope was cancelled at ON_DESTROY
			// before the dispatcher picked it up. A release inside the body as well would let job A's
			// completion clear a claim job B had already re-taken, which is the duplicate work the
			// claim exists to prevent.
			job.invokeOnCompletion { canonicalisationsInFlight.remove(projectPath) }
			return null
		}
	}

	/** Shown in the clipboard preview on Android 13+, so it is user-facing and lives in resources. */
	private val clipLabel: String = context.getString(R.string.clip_label_deeplink)

	init {
		label = context.getString(R.string.action_create_link)
		icon = ContextCompat.getDrawable(context, R.drawable.ic_copy)
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)

		if (!visible) {
			return
		}

		val activity =
			data.getActivity()
				?: run {
					markInvisible()
					return
				}

		// Hidden rather than shown-and-failing: the states that produce no link are properties of how
		// the project was opened, not transient ones the user could correct by tapping again.
		//
		// Only the cheap predicates run here. prepare() is called synchronously, from the touch
		// handler, for every action in this menu on every open, and building the URL just to compare
		// it against null cost ~17 percent-encoding passes plus a full parse of the result -- all
		// discarded, then paid again on the tap.
		if (urlFor(activity) == null) {
			markInvisible()
		}
	}

	override fun EditorHandlerActivity.doAction(data: ActionData): Boolean {
		// Recomputed rather than cached from prepare(): the menu can outlive the state it was built
		// from (the tab can be closed, the project re-synced) and a stale link is worse than none.
		// prepare() hides this action for every state that is stably unlinkable, so getting here with
		// nothing to copy means something moved underneath the open menu -- rare, but a tap that does
		// nothing at all reads as a broken button, so say so.
		val url =
			urlFor(this) ?: run {
				flashError(R.string.msg_deeplink_cannot_create)
				return false
			}

		copyToClipboard(url, label = clipLabel)
		flashSuccess(R.string.msg_deeplink_copied)
		return true
	}

	/**
	 * The most recently built URL, with the target it was built from.
	 *
	 * `prepare()` has to know whether a URL can be built at all, not merely whether the pieces exist:
	 * [DeepLinkRequest.buildUrl] refuses paths the reader would reject, and those are permanent
	 * properties of the file, so an item shown without asking it fails on every tap forever. But
	 * building the URL and discarding it made `prepare()` -- which runs for every action on every
	 * menu open, in the touch handler -- pay for a full encode-and-reparse it threw away, then pay
	 * again on the tap.
	 *
	 * Keeping the last result settles both: `prepare()` builds, and the tap that follows reuses it,
	 * because the cursor cannot have moved in between. Read and written only from the main thread
	 * (`requiresUIThread`), so it needs no synchronisation.
	 */
	private var lastBuilt: Pair<LinkTarget, String>? = null

	/** The URL for the current tab, built at most once per menu open, or `null` if there is none. */
	private fun urlFor(activity: EditorHandlerActivity): String? {
		val target = linkTarget(activity) ?: return null
		lastBuilt?.let { (built, url) -> if (built == target) return url }
		val url = target.toUrl() ?: return null
		lastBuilt = target to url
		return url
	}

	/**
	 * Everything a link needs, gathered without building one.
	 */
	private data class LinkTarget(
		val projectName: String,
		val relativePath: String,
		val line: Int,
		val column: Int,
	) {
		fun toUrl(): String? =
			DeepLinkRequest.buildUrl(
				projectName = projectName,
				filePath = relativePath,
				line = line,
				column = column,
			)
	}

	/**
	 * The link target for the file in [activity]'s currently selected tab, or `null` if that file has
	 * no link that would resolve anywhere.
	 */
	private fun linkTarget(activity: EditorHandlerActivity): LinkTarget? {
		val editorView = activity.getCurrentEditor() ?: return null

		// The editor, then the file from it. CodeEditorView.file is itself `editor?.file`, so asking
		// for the file first and then null-checking the editor separately would be asking the same
		// question twice and dressing the second as a safeguard.
		val editor = editorView.editor ?: return null
		val file = editor.file ?: return null

		val projectPath = IProjectManager.getInstance().projectDirPath
		if (projectPath.isBlank()) {
			return null
		}
		val projectDir = File(projectPath)

		// The two free rejections first. Both are pure and settle the answer outright, so asking them
		// before linkableProject -- which dispatches a canonicalisation as a side effect -- avoids
		// launching filesystem work whose result could not change the verdict.
		//
		// buildUrl's own rule, called rather than restated: a direct child named ".foo" satisfies the
		// containment check below (that one compares parents, not names) but is not a project a link
		// can name.
		if (!DeepLinkRequest.isLinkableProjectName(projectDir.name)) {
			return null
		}

		val relativePath = projectRelativePathOrNull(projectDir, file) ?: return null

		// A deep link can only ever name <projectsRoot>/<name>, but a project can be opened from
		// anywhere -- the file picker, Recents, a clone destination. For one of those there is no URL
		// that resolves on this device, let alone on the recipient's, so there is nothing honest to
		// put on the clipboard.
		if (linkableProject(activity, projectPath) != true) {
			return null
		}

		// The editor is zero-based at both ends; the URL scheme is one-based. Both coordinates are
		// always written, never omitted at 1:1 -- see buildUrl, which refuses the coordinate-free and
		// column-only shapes precisely because the reader mis-handles them.
		val cursor = editor.cursor
		return LinkTarget(
			projectName = projectDir.name,
			relativePath = relativePath,
			line = cursor.leftLine + 1,
			column = cursor.leftColumn + 1,
		)
	}
}

/**
 * [file]'s path relative to [projectDir], always '/'-separated, or `null` when [file] does not lie
 * inside [projectDir].
 *
 * Split out of the action because it is the security-relevant half and the only part worth testing
 * on its own: `relativeToOrNull` walks *up* with ".." when the file is outside rather than failing,
 * so containment has to be checked on the result and not merely on the call succeeding. Without that
 * check a link could name a file outside the project it claims.
 */
@VisibleForTesting
internal fun projectRelativePathOrNull(
	projectDir: File,
	file: File,
): String? {
	val relative = file.relativeToOrNull(projectDir)?.invariantSeparatorsPath ?: return null
	if (relative.isEmpty() || relative == ".." || relative.startsWith("../")) {
		return null
	}
	return relative
}
