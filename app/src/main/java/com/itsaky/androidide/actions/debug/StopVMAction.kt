package com.itsaky.androidide.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData

/**
 * @author Akash Yadav
 */
class StopVMAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_stop) {
    override val id = "ide.debug.stop-vm"
    override var label = context.getString(R.string.debugger_stop)
    override val order = 4

    override suspend fun execAction(data: ActionData) {
        // TODO: Stop VM
    }
}