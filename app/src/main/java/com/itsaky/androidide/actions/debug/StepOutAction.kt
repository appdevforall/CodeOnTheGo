package com.itsaky.androidide.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.lsp.IDEDebugClientImpl

/**
 * @author Akash Yadav
 */
class StepOutAction(
    context: Context
) : AbstractStepAction(R.drawable.ic_step_out) {
    override val id = "ide.debug.step-out"
    override var label = context.getString(R.string.debugger_step_out)
    override val order = 3

    override suspend fun execAction(data: ActionData) {
        IDEDebugClientImpl.stepOut()
    }
}