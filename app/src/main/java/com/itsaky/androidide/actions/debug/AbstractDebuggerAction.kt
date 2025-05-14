package com.itsaky.androidide.actions.debug

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.lsp.IDEDebugClientImpl

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

    protected val debugClient: IDEDebugClientImpl
        get() = IDEDebugClientImpl

    override fun prepare(data: ActionData) {
        super.prepare(data)

        icon = ContextCompat.getDrawable(data.requireContext(), iconRes)
    }
}