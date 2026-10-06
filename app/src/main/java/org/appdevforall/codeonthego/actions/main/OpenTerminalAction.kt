package org.appdevforall.codeonthego.actions.main

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.ActionItem
import org.appdevforall.codeonthego.actions.markInvisible
import org.appdevforall.codeonthego.activities.TerminalActivity
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.utils.applyMultiWindowFlags

class OpenTerminalAction(context: Context) : ActionItem {

    override val id: String = ID

    companion object {
        const val ID = "ide.main.openTerminal"
    }

    override var label: String = context.getString(R.string.title_terminal)
    override var visible: Boolean = true
    override var enabled: Boolean = true
    override var icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_terminal)
    override var requiresUIThread: Boolean = true
    override var location: ActionItem.Location = ActionItem.Location.MAIN_SCREEN
    override val order: Int = 4

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.MAIN_TERMINAL

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (data.get(Context::class.java) == null) {
            markInvisible()
        }
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.get(Context::class.java) ?: return false
        val intent = Intent(context, TerminalActivity::class.java)
            .applyMultiWindowFlags(context)
        context.startActivity(intent)
        return true
    }
}
