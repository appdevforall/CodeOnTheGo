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
class StopVMAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_stop) {
    override val id = "ide.debug.stop-vm"
    override var label = context.getString(R.string.debugger_stop)
    override val order = 4

    override suspend fun execAction(data: ActionData) {
        val currentState = debugClient.viewModel?.connectionState?.value
        if (currentState == null || !isEnabledForState(currentState)) {
            Log.d("DebugAction", "StopVMAction blocked - invalid state: $currentState")
            return
        }

        Log.d("DebugAction", "StopVMAction executed! state=$currentState")
        IDEDebugClientImpl.stopVM()
    }

    // VM control actions están habilitadas cuando el estado es mayor que DETACHED
    override fun isEnabledForState(state: DebuggerConnectionState): Boolean {
        return state > DebuggerConnectionState.DETACHED
    }
}