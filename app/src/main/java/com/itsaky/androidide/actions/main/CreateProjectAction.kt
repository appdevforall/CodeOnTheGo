package com.itsaky.androidide.actions.main

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.idetooltips.TooltipTag

class CreateProjectAction(context: Context) : ActionItem {

    override val id: String = ID

    companion object {
        const val ID = "ide.main.createProject"
    }

    override var label: String = context.getString(R.string.create_project)
    override var visible: Boolean = true
    override var enabled: Boolean = true
    override var icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_add)
    override var requiresUIThread: Boolean = true
    override var location: ActionItem.Location = ActionItem.Location.MAIN_SCREEN
    override val order: Int = 0

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.PROJECT_NEW

    override fun prepare(data: ActionData) {
        super.prepare(data)
        val context = data.get(Context::class.java)
        if (context !is MainActivity) {
            markInvisible()
        }
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.get(Context::class.java) as? MainActivity ?: return false
        return context.showCreateProject()
    }
}
