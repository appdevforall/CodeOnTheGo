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
import com.itsaky.androidide.utils.isDeepLinkTargetOfOpenProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

		/** Shown in the clipboard preview on Android 13+, so it names the app rather than the action. */
		private const val CLIP_LABEL = "Code on the Go link"

		/**
		 * Memoised answer to "is the open project one a link can name", keyed by its path. Holds no
		 * Context, so it is safe in a companion; the answer only changes when a project is opened.
		 */
		@Volatile
		private var linkableProject: Pair<String, Boolean>? = null

		private fun cachedLinkable(projectPath: String): Boolean? = linkableProject?.takeIf { it.first == projectPath }?.second

		/**
		 * Whether a link can name the open project, or `null` while that is still being decided off
		 * this thread.
		 *
		 * `prepare()` has to answer synchronously, and the full rule
		 * ([isDeepLinkTargetOfOpenProject]) canonicalises both sides -- filesystem work that REVIEW.md
		 * §3 and ADR 0007 require be moved rather than suppressed, since the StrictMode whitelist is
		 * for vendored code we cannot change and never for our own. So the common case is decided
		 * without touching the disk at all, and only the residue goes to [Dispatchers.IO].
		 */
		private fun linkableProject(
			activity: EditorHandlerActivity,
			projectPath: String,
		): Boolean? {
			cachedLinkable(projectPath)?.let { return it }

			// Equal path strings name the same directory, so canonicalising both sides could only
			// agree -- decidable here with no filesystem call, and this is the path every project
			// opened from the projects list takes. (The name half of the rule is trivially satisfied:
			// the caller derives the project name from this very path.)
			val parent = File(projectPath).parentFile
			if (parent != null && parent.absolutePath == projectsRoot().absolutePath) {
				linkableProject = projectPath to true
				return true
			}

			// The two differ as text, so only canonicalisation can say whether a symlink still makes
			// them one directory. That is the rare case -- a project opened from the file picker or a
			// clone destination -- and it is answered off the main thread. The item stays hidden until
			// the result lands, and the next menu open reads it from the cache.
			activity.lifecycleScope.launch(Dispatchers.IO) {
				val linkable = isDeepLinkTargetOfOpenProject(projectPath, File(projectPath).name, projectsRoot())
				linkableProject = projectPath to linkable
			}
			return null
		}
	}

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

		copyToClipboard(url, label = CLIP_LABEL)
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

		// relativeToOrNull walks up with ".." when the file lies outside the project rather than
		// failing, so containment has to be checked on the result and not just on the call succeeding.
		val relativePath = file.relativeToOrNull(projectDir)?.invariantSeparatorsPath ?: return null
		if (relativePath.isEmpty() || relativePath == ".." || relativePath.startsWith("../")) {
			return null
		}

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
