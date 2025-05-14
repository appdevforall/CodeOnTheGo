package com.itsaky.androidide.services.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.debug.PauseResumeVMAction
import com.itsaky.androidide.actions.debug.RestartVMAction
import com.itsaky.androidide.actions.debug.StepIntoAction
import com.itsaky.androidide.actions.debug.StepOutAction
import com.itsaky.androidide.actions.debug.StepOverAction
import com.itsaky.androidide.actions.debug.StopVMAction
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
class DebuggerService : Service() {

    inner class Binder : android.os.Binder() {
        fun getService(): DebuggerService = this@DebuggerService
    }

    private val actionsRegistry = ActionsRegistry.getInstance()
    private lateinit var actionsList: List<ActionItem>
    private lateinit var overlayManager: DebugOverlayManager
    private val binder = Binder()

    companion object {
        private val logger = LoggerFactory.getLogger(DebuggerService::class.java)
    }

    override fun onCreate() {
        logger.debug("onCreate()")
        super.onCreate()

        val context = this
        actionsList = mutableListOf<ActionItem>().apply {
            add(PauseResumeVMAction(context))
            add(StepOverAction(context))
            add(StepIntoAction(context))
            add(StepOutAction(context))
            add(StopVMAction(context))
            add(RestartVMAction(context))
        }

        this.actionsList.forEach(actionsRegistry::registerAction)
        this.overlayManager = DebugOverlayManager.create(this)
    }

    override fun onDestroy() {
        actionsList.forEach(actionsRegistry::unregisterAction)
        super.onDestroy()
    }

    fun showOverlay() {
        logger.debug("showOverlay()")
        this.overlayManager.show()
    }

    fun hideOverlay() {
        logger.debug("hideOverlay()")
        this.overlayManager.hide()
    }

    override fun onBind(intent: Intent?): IBinder =
        this.binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        logger.debug("onStartCommand()")
        // if the service is killed by the system, there is no point in restarting it
        return START_NOT_STICKY
    }
}