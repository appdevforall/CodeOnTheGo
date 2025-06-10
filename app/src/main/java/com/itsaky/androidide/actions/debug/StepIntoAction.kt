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
class StepIntoAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_step_into) {
    override val id = "ide.debug.step-into"
    override var label = context.getString(R.string.debugger_step_into)
    override val order = 2

    override fun isDebuggerConectionEnabled(state: DebuggerConnectionState): Boolean {
        return state == DebuggerConnectionState.SUSPENDED
    }

    override suspend fun execAction(data: ActionData) {
        val debugClientState = debugClient.viewModel?.connectionState?.value
        if (debugClientState == null || !isDebuggerConectionEnabled(debugClientState)) {
            Log.d("DebugAction", "StepIntoAction blocked - invalid state: $debugClientState")
            return
        }

        Log.d("DebugAction", "StepIntoAction executed! state=$debugClientState")
        IDEDebugClientImpl.stepInto()
    }
}