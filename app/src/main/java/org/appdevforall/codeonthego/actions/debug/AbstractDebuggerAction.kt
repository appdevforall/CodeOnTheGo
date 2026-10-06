package org.appdevforall.codeonthego.actions.debug

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.actions.ActionItem
import org.appdevforall.codeonthego.actions.requireContext
import org.appdevforall.codeonthego.lsp.IDEDebugClientImpl

/**
 * @author Akash Yadav
 */
abstract class AbstractDebuggerAction(
    @DrawableRes private val iconRes: Int
) : ActionItem {

    // debugger actions must always be executed in a background thread
    override var requiresUIThread = false
    override var location = ActionItem.Location.DEBUGGER_ACTIONS

    override var visible = true
    override var enabled = true
    override var icon: Drawable? = null

    protected val debugClient: IDEDebugClientImpl?
        get() = IDEDebugClientImpl.getInstance()

    protected open fun checkEnabled(data: ActionData): Boolean = debugClient?.isVmConnected() == true

    override fun prepare(data: ActionData) {
        super.prepare(data)

        icon = ContextCompat.getDrawable(data.requireContext(), iconRes)
        enabled = checkEnabled(data)
    }
}
