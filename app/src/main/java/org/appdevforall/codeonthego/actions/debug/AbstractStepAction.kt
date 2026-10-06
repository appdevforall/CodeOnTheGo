package org.appdevforall.codeonthego.actions.debug

import androidx.annotation.DrawableRes
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.viewmodel.DebuggerConnectionState

abstract class AbstractStepAction(
    @DrawableRes iconRes: Int
): AbstractDebuggerAction(iconRes) {

    override fun checkEnabled(data: ActionData): Boolean {
        val client = debugClient ?: return false
        return client.connectionState >= DebuggerConnectionState.AWAITING_BREAKPOINT
    }
}
