package com.itsaky.androidide.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData

/**
 * @author Akash Yadav
 */
class StepOverAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_step_over) {
    override val id = "ide.debug.step-over"
    override var label = context.getString(R.string.debugger_step_over)
    override val order = 1

    override suspend fun execAction(data: ActionData) {
        // TODO: step into
    }
}