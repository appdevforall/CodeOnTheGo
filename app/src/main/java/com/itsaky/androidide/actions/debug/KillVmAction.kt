package com.itsaky.androidide.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.idetooltips.TooltipTag

/**
 * @author Akash Yadav
 */
class KillVmAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_stop) {
    override val id = "ide.debug.stop-vm"
    override var label = context.getString(R.string.debugger_kill)
    override val order = 4
    override var tooltipTag = TooltipTag.DEBUGGER_ACTION_KILL

    override suspend fun execAction(data: ActionData) {
        debugClient.killVm()
    }
}