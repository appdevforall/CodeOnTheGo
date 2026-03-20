package com.itsaky.androidide.actions.main

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.idetooltips.TooltipTag

class PreferencesAction(context: Context) : ActionItem {

    override val id: String = ID

    companion object {
        const val ID = "ide.main.preferences"
    }

    override var label: String = context.getString(R.string.msg_preferences)
    override var visible: Boolean = true
    override var enabled: Boolean = true
    override var icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_settings)
    override var requiresUIThread: Boolean = true
    override var location: ActionItem.Location = ActionItem.Location.MAIN_SCREEN
    override val order: Int = 5

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.MAIN_PREFERENCES

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (data.get(Context::class.java) == null) {
            markInvisible()
        }
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.get(Context::class.java) ?: return false
        context.startActivity(Intent(context, PreferencesActivity::class.java))
        return true
    }
}
