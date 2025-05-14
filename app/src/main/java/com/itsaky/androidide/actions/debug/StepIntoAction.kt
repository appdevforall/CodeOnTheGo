package com.itsaky.androidide.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData

/**
 * @author Akash Yadav
 */
class StepIntoAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_step_into) {
    override val id = "ide.debug.step-into"
    override var label = context.getString(R.string.debugger_step_into)
    override val order = 2

    override suspend fun execAction(data: ActionData) {
        // TODO: step into
    }
}