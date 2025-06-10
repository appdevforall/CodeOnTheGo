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
class StepOverAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_step_over) {
    override val id = "ide.debug.step-over"
    override var label = context.getString(R.string.debugger_step_over)
    override val order = 1

    override fun isDebuggerConectionEnabled(state: DebuggerConnectionState): Boolean {
        return state == DebuggerConnectionState.SUSPENDED
    }

    override suspend fun execAction(data: ActionData) {
        val debugClientState = debugClient.viewModel?.connectionState?.value
        if (debugClientState == null || !isDebuggerConectionEnabled(debugClientState)) {
            Log.d("DebugAction", "StepOverAction blocked - invalid state: $debugClientState")
            return
        }

        Log.d("DebugAction", "StepOverAction executed! state=$debugClientState")
        IDEDebugClientImpl.stepOver()
    }
}