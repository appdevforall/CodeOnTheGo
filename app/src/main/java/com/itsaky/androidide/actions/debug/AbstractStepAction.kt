package com.itsaky.androidide.actions.debug

import androidx.annotation.DrawableRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.viewmodel.DebuggerConnectionState

abstract class AbstractStepAction(
    @DrawableRes iconRes: Int
): AbstractDebuggerAction(iconRes) {

    override fun checkEnabled(data: ActionData): Boolean {
        val client = debugClient ?: return false
        return client.connectionState >= DebuggerConnectionState.AWAITING_BREAKPOINT
    }
}
