package com.itsaky.androidide.actions.main

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R.string
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY

class DocsAction(context: Context) : ActionItem {

    override val id: String = ID

    companion object {
        const val ID = "ide.main.docs"
    }

    override var label: String = context.getString(R.string.btn_docs)
    override var visible: Boolean = true
    override var enabled: Boolean = true
    override var icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_action_help_outlined)
    override var requiresUIThread: Boolean = true
    override var location: ActionItem.Location = ActionItem.Location.MAIN_SCREEN
    override val order: Int = 7

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.MAIN_HELP

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (data.get(Context::class.java) == null) {
            markInvisible()
        }
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.get(Context::class.java) ?: return false
        val intent =
            Intent(context, HelpActivity::class.java).apply {
                putExtra(CONTENT_KEY, context.getString(string.docs_url))
                putExtra(CONTENT_TITLE_KEY, context.getString(string.back_to_cogo))
            }
        context.startActivity(intent)
        return true
    }
}
