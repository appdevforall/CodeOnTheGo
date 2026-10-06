package org.appdevforall.codeonthego.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import org.appdevforall.codeonthego.actions.ActionData
import org.appdevforall.codeonthego.idetooltips.TooltipTag

/**
 * @author Akash Yadav
 */
class StepOverAction(
    context: Context
) : AbstractStepAction(R.drawable.ic_step_over) {
    override val id = "ide.debug.step-over"
    override var label = context.getString(R.string.debugger_step_over)
    override val order = 1
    override var tooltipTag = TooltipTag.DEBUGGER_ACTION_STEP_OVER

    override suspend fun execAction(data: ActionData) {
        debugClient?.stepOver()
    }
}
