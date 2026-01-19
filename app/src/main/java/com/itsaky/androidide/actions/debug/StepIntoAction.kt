package com.itsaky.androidide.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.idetooltips.TooltipTag

/**
 * @author Akash Yadav
 */
class StepIntoAction(
    context: Context
) : AbstractStepAction(R.drawable.ic_step_into) {
    override val id = "ide.debug.step-into"
    override var label = context.getString(R.string.debugger_step_into)
    override val order = 2
    override var tooltipTag = TooltipTag.DEBUGGER_ACTION_STEP_INTO

    override suspend fun execAction(data: ActionData) {
        debugClient.stepInto()
    }
}