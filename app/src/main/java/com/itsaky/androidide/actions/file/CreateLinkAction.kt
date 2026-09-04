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
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.deepLinkTargetOfOpenProjectWithoutIo
import com.itsaky.androidide.utils.isDeepLinkTargetOfOpenProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

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
		 * Memoised answer to "is the open project one a link can name", keyed by its path. Holds no
		 * Context, so it is safe in a companion; the answer only changes when a project is opened.
		 */
		@Volatile
		private var linkableProject: Pair<String, Boolean>? = null

		/** Path whose canonicalisation is already running, so N menu opens launch one job, not N. */
		@Volatile
		private var linkabilityInFlight: String? = null

		/**
		 * Whether a link can name the open project, or `null` while that is still being decided off
		 * this thread.
		 *
		 * `prepare()` has to answer synchronously, and the full rule canonicalises both sides --
		 * filesystem work that REVIEW.md §3 and ADR 0007 require be moved rather than suppressed,
		 * since the StrictMode whitelist is for vendored code we cannot change and never for our own.
		 * So [deepLinkTargetOfOpenProjectWithoutIo] settles the common case with no disk call, and
		 * only the residue -- paths that differ as text, where a symlink might still make them one
		 * directory -- goes to [Dispatchers.IO].
		 *
		 * Known limitation while that rare case is pending: the item is absent from the menu until the
		 * answer lands, and reappears on the next open. Showing it optimistically instead would mean
		 * a tap that fails, which reads worse; the population this can affect is a project opened from
		 * the file picker or a clone destination, which is usually not linkable anyway.
		 */
		private fun linkableProject(
			activity: EditorHandlerActivity,
			projectPath: String,
		): Boolean? {
			linkableProject?.takeIf { it.first == projectPath }?.let { return it.second }

			val root = runCatching { projectsRoot() }.getOrNull() ?: return null
			val projectName = File(projectPath).name

			deepLinkTargetOfOpenProjectWithoutIo(projectPath, projectName, root)?.let {
				linkableProject = projectPath to it
				return it
			}

			// One job per path, not one per menu open: ActionMenuUtils calls prepare() every time the
			// popup is built, and canonicalising the same path N times in parallel is pure waste.
			if (linkabilityInFlight == projectPath) return null
			linkabilityInFlight = projectPath

			activity.lifecycleScope.launch(Dispatchers.IO) {
				// Handled here rather than left to the crash wrapper (REVIEW.md §1): a failure to
				// canonicalise says nothing about whether the project is linkable, so it is logged and
				// left uncached, and the next menu open retries.
				runCatching { isDeepLinkTargetOfOpenProject(projectPath, projectName, root) }
					.onSuccess { linkable ->
						// Published only if this is still the project in question. A slow answer for a
						// project the user has since left must not overwrite the newer one's verdict.
						if (IProjectManager.getInstance().projectDirPath == projectPath) {
							linkableProject = projectPath to linkable
						}
					}.onFailure { log.warn("Could not determine whether {} can be linked", projectPath, it) }

				if (linkabilityInFlight == projectPath) {
					linkabilityInFlight = null
				}
			}
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
		if (linkForCurrentFile(activity) == null) {
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
			linkForCurrentFile(this) ?: run {
				flashError(R.string.msg_deeplink_cannot_create)
				return false
			}

		copyToClipboard(url, label = clipLabel)
		flashSuccess(R.string.msg_deeplink_copied)
		return true
	}

	/**
	 * The deep link for the file in [activity]'s currently selected tab, or `null` if that file has no
	 * link that would resolve anywhere.
	 */
	private fun linkForCurrentFile(activity: EditorHandlerActivity): String? {
		val editorView = activity.getCurrentEditor() ?: return null
		val file = editorView.file ?: return null

		val projectPath = IProjectManager.getInstance().projectDirPath
		if (projectPath.isBlank()) {
			return null
		}
		val projectDir = File(projectPath)

		// A deep link can only ever name <projectsRoot>/<name>, but a project can be opened from
		// anywhere -- the file picker, Recents, a clone destination. For one of those there is no URL
		// that resolves on this device, let alone on the recipient's, so there is nothing honest to
		// put on the clipboard. Reusing the reader's own containment rule keeps the two from drifting.
		if (linkableProject(activity, projectPath) != true) {
			return null
		}

		val relativePath = projectRelativePathOrNull(projectDir, file) ?: return null

		// A cursor is required, not optional. Without one the link would carry no line or column, and
		// a coordinate-free link is the one shape parse() can misread: a file path whose own trailing
		// segments look like "line"/"column" gets peeled apart as metadata. buildUrl now rejects that
		// case outright, so treating a missing cursor as "no link" is what keeps this action from
		// silently producing nothing at the moment of the tap. The editor is null only before its view
		// is inflated, and prepare() re-runs on every menu open.
		val cursor = editorView.editor?.cursor ?: return null

		// The editor is zero-based at both ends; the URL scheme is one-based. See buildUrl's docs.
		return DeepLinkRequest.buildUrl(
			projectName = projectDir.name,
			filePath = relativePath,
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
