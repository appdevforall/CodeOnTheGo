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
class StepOutAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_step_out) {
    override val id = "ide.debug.step-out"
    override var label = context.getString(R.string.debugger_step_out)
    override val order = 3

    // Step actions solo están habilitadas cuando el debugger está suspendido
    override fun isEnabledForState(state: DebuggerConnectionState): Boolean {
        return state == DebuggerConnectionState.SUSPENDED
    }

    override suspend fun execAction(data: ActionData) {
        val currentState = debugClient.viewModel?.connectionState?.value
        if (currentState == null || !isEnabledForState(currentState)) {
            Log.d("DebugAction", "StepOutAction blocked - invalid state: $currentState")
            return
        }

        Log.d("DebugAction", "StepOutAction executed! state=$currentState")
        IDEDebugClientImpl.stepOut()
    }
}