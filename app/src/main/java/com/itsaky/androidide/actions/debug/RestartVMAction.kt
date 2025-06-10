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

    // VM control actions están habilitadas cuando el estado es mayor que DETACHED
    override fun isEnabledForState(state: DebuggerConnectionState): Boolean {
        return state > DebuggerConnectionState.DETACHED
    }

    override suspend fun execAction(data: ActionData) {
        val currentState = debugClient.viewModel?.connectionState?.value
        if (currentState == null || !isEnabledForState(currentState)) {
            Log.d("DebugAction", "RestartVMAction blocked - invalid state: $currentState")
            return
        }

        Log.d("DebugAction", "RestartVMAction executed! state=$currentState")
        IDEDebugClientImpl.restartVM()
    }
}