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
class PauseResumeVMAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_pause) {

    override val id = "ide.debug.pause-resume"
    override var label = context.getString(R.string.debugger_pause)
    override val order = 0

    override fun prepare(data: ActionData) {
        super.prepare(data)

        // TODO: check if VM is paused
        //    then updated the following accordingly
        //    1. label = "Resume"/"Pause"
        //    2. icon = ic_resume/ic_pause
    }

    override fun isDebuggerConectionEnabled(state: DebuggerConnectionState): Boolean {
        return state > DebuggerConnectionState.DETACHED
    }

    override suspend fun execAction(data: ActionData) {
        val debugClientState = debugClient.viewModel?.connectionState?.value
        if (debugClientState == null || !isDebuggerConectionEnabled(debugClientState)) {
            Log.d("DebugAction", "PauseResumeVMAction blocked - invalid state: $debugClientState")
            return
        }

        Log.d("DebugAction", "PauseResumeVMAction executed! state=$debugClientState")
        IDEDebugClientImpl.pauseResumeVM()
    }
}