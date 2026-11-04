package com.itsaky.androidide.actions.etc

import android.content.Context
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.resources.R

class ReplaceInProjectAction() : EditorActivityAction() {
	override val id: String = ID
	override var requiresUIThread: Boolean = true
	override var order: Int = 0
	override var location: ActionItem.Location = ActionItem.Location.EDITOR_FIND_ACTION_MENU

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.EDITOR_TOOLBAR_FIND_IN_PROJECT

	companion object {
		const val ID = "ide.editor.replace.inProject"
	}

	constructor(context: Context, order: Int) : this() {
		this.label = context.getString(R.string.menu_replace_project)
		this.icon = ContextCompat.getDrawable(context, R.drawable.ic_search_project)
		this.order = order
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)
		data.getActivity()
			?: run {
				markInvisible()
				return
			}

		val gradleBuild = IProjectManager.getInstance().gradleBuild
		if (gradleBuild == null || gradleBuild.subProjectCount == 0) {
			markInvisible()
			return
		}

		visible = true
		enabled = true
	}

	override suspend fun execAction(data: ActionData): Boolean {
		val context = data.getActivity() ?: return false
		val dialog = context.replaceInProjectDialog ?: return false

		return run {
			dialog.show()
			true
		}
	}
}
