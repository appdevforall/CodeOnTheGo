package com.itsaky.androidide.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData

/**
 * @author Akash Yadav
 */
class RestartVMAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_restart) {
    override val id = "ide.debug.restart-vm"
    override var label = context.getString(R.string.debugger_restart)
    override val order = 5

    override suspend fun execAction(data: ActionData) {
        // TODO: Restart VM
    }
}