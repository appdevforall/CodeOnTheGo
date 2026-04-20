package com.itsaky.androidide.actions.main

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.viewmodel.MainViewModel

class DeleteProjectAction(context: Context) : ActionItem {

    override val id: String = ID

    companion object {
        const val ID = "ide.main.deleteProject"
    }

    override var label: String = context.getString(R.string.msg_delete_existing_project)
    override var visible: Boolean = true
    override var enabled: Boolean = true
    override var icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_delete)
    override var requiresUIThread: Boolean = true
    override var location: ActionItem.Location = ActionItem.Location.MAIN_SCREEN
    override val order: Int = 3

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.MAIN_PROJECT_DELETE

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (data.get(MainViewModel::class.java) == null || data.get(Context::class.java) == null) {
            markInvisible()
        }
    }

    override suspend fun execAction(data: ActionData): Any {
        val viewModel = data.get(MainViewModel::class.java) ?: return false
        viewModel.setScreen(MainViewModel.SCREEN_DELETE_PROJECTS)
        return true
    }
}
