package com.itsaky.androidide.actions.debug

import androidx.annotation.DrawableRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.lsp.IDEDebugClientImpl
import com.itsaky.androidide.viewmodel.DebuggerConnectionState

abstract class AbstractStepAction(
    @DrawableRes iconRes: Int
): AbstractDebuggerAction(iconRes) {

    override fun checkEnabled(data: ActionData): Boolean =
        IDEDebugClientImpl.connectionState >= DebuggerConnectionState.AWAITING_BREAKPOINT
}