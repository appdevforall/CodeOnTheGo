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
		if (!isDeepLinkTargetOfOpenProject(projectPath, projectDir.name, projectsRoot())) {
			return null
		}

		// relativeToOrNull walks up with ".." when the file lies outside the project rather than
		// failing, so containment has to be checked on the result and not just on the call succeeding.
		val relativePath = file.relativeToOrNull(projectDir)?.invariantSeparatorsPath ?: return null
		if (relativePath.isEmpty() || relativePath == ".." || relativePath.startsWith("../")) {
			return null
		}

		// The editor is zero-based at both ends; the URL scheme is one-based. See buildUrl's docs.
		val cursor = editorView.editor?.cursor
		return DeepLinkRequest.buildUrl(
			projectName = projectDir.name,
			filePath = relativePath,
			line = cursor?.let { it.leftLine + 1 },
			column = cursor?.let { it.leftColumn + 1 },
		)
	}
}
