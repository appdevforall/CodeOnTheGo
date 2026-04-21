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
import com.itsaky.androidide.utils.FeatureFlags

class CloneRepositoryAction(context: Context) : ActionItem {

    override val id: String = ID

    companion object {
        const val ID = "ide.main.cloneRepository"
    }

    override var label: String = context.getString(R.string.clone_git_project)
    override var visible: Boolean = true
    override var enabled: Boolean = true
    override var icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_clone_repo)
    override var requiresUIThread: Boolean = true
    override var location: ActionItem.Location = ActionItem.Location.MAIN_SCREEN
    override val order: Int = 2

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.MAIN_GIT

    override fun prepare(data: ActionData) {
        super.prepare(data)
        val context = data.get(Context::class.java)
        if (context !is MainActivity) {
            markInvisible()
        }
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.get(Context::class.java) as? MainActivity ?: return false
        return context.showCloneRepository()
    }
}
