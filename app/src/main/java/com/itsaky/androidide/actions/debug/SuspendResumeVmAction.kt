package com.itsaky.androidide.actions.debug

import android.content.Context
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.idetooltips.TooltipTag

/**
 * @author Akash Yadav
 */
class SuspendResumeVmAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_pause) {

    override val id = "ide.debug.suspend-resume"
    override var label = context.getString(R.string.debugger_suspend)
    override val order = 0
    override var tooltipTag = TooltipTag.DEBUGGER_ACTION_PAUSE_RESUME

    override fun prepare(data: ActionData) {
        super.prepare(data)
        val context = data.requireContext()
        val isSuspended = debugClient.isVmSuspended()
        if (isSuspended) {
            label = context.getString(R.string.debugger_resume)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_run)
        } else {
            label = context.getString(R.string.debugger_suspend)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_pause)
        }
    }

    override suspend fun execAction(data: ActionData) {
        if (debugClient.isVmSuspended()) {
            debugClient.resumeVm()
        } else {
            debugClient.suspendVm()
        }
    }
}