package com.itsaky.androidide.actions.debug

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.utils.IntentUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * @author Akash Yadav
 */
class RestartVmAction(
    context: Context
) : AbstractDebuggerAction(R.drawable.ic_restart) {
    override val id = "ide.debug.restart-vm"
    override var label = context.getString(R.string.debugger_restart)
    override val order = 5

    companion object {
        val RESTART_DELAY = 1.seconds
    }

    override suspend fun execAction(data: ActionData) {
        val context = data.requireContext()

        // kill the current VM
        debugClient.killVm()

        // wait for some time
        delay(RESTART_DELAY)

        // then launch the debugee again, in debug mode
        IntentUtils.launchApp(
            context = context,
            packageName = debugClient.debugeePackage,
            debug = true,
        )
    }
}
