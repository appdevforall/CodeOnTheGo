package com.itsaky.androidide.actions.debug

import android.content.Context
import android.util.Log
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.lsp.IDEDebugClientImpl
import com.itsaky.androidide.viewmodel.DebuggerConnectionState

/**
 * @author Akash Yadav
 */
class RestartVMAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_restart) {
    override val id = "ide.debug.restart-vm"
    override var label = context.getString(R.string.debugger_restart)
    override val order = 5

    override fun isDebuggerConectionEnabled(state: DebuggerConnectionState): Boolean {
        return state > DebuggerConnectionState.DETACHED
    }

    override suspend fun execAction(data: ActionData) {
        val debugClientState = debugClient.viewModel?.connectionState?.value
        if (debugClientState == null || !isDebuggerConectionEnabled(debugClientState)) {
            Log.d("DebugAction", "RestartVMAction blocked - invalid state: $debugClientState")
            return
        }

        Log.d("DebugAction", "RestartVMAction executed! state=$debugClientState")
        IDEDebugClientImpl.restartVM()
    }
}