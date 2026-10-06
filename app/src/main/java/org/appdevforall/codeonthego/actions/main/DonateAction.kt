package org.appdevforall.codeonthego.actions.main

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.ActionItem
import org.appdevforall.codeonthego.actions.markInvisible
import org.appdevforall.codeonthego.actions.requireContext
import org.appdevforall.codeonthego.utils.UrlManager

class DonateAction(context: Context) : ActionItem {

    override val id: String = ID

    companion object {
        const val ID = "ide.main.donate"
    }

    override var label: String = context.getString(R.string.btn_donate)
    override var visible: Boolean = true
    override var enabled: Boolean = true
    override var icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_heart)
    override var requiresUIThread: Boolean = true
    override var location: ActionItem.Location = ActionItem.Location.MAIN_SCREEN
    override val order: Int = 6

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (data.get(Context::class.java) == null) {
            markInvisible()
        }
        markInvisible() // Until we allow donations
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.requireContext()
        UrlManager.openUrl(context.getString(R.string.sponsor_url), null, context)
        return true
    }
}
