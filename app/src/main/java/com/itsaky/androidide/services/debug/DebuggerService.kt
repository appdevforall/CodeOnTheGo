package com.itsaky.androidide.services.debug

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.debug.PauseResumeVMAction
import com.itsaky.androidide.actions.debug.RestartVMAction
import com.itsaky.androidide.actions.debug.StepIntoAction
import com.itsaky.androidide.actions.debug.StepOutAction
import com.itsaky.androidide.actions.debug.StepOverAction
import com.itsaky.androidide.actions.debug.StopVMAction
import org.slf4j.LoggerFactory
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.java.JavaLanguageServer

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

    private val autoShutdownHandler = Handler(Looper.getMainLooper())
    private var autoShutdownRunnable: Runnable? = null
    private var hasClientConnected = false

    companion object {
        private val logger = LoggerFactory.getLogger(DebuggerService::class.java)
        private var instance: DebuggerService? = null

        fun currentInstance(): DebuggerService? = instance
    }

    override fun onCreate() {
        logger.debug("onCreate()")
        super.onCreate()
        instance = this

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

        scheduleAutoShutdownIfNoClient()
    }

    override fun onDestroy() {
        cancelAutoShutdown()
        instance = null
        actionsList.forEach(actionsRegistry::unregisterAction)

        val adapter = ILanguageServerRegistry.getDefault()
            .getServer(JavaLanguageServer.SERVER_ID)
            ?.debugAdapter

        (adapter as? AutoCloseable)?.close()

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

    override fun onBind(intent: Intent?): IBinder = this.binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        logger.debug("onStartCommand()")
        return START_NOT_STICKY
    }

    fun markClientConnected() {
        hasClientConnected = true
        cancelAutoShutdown()
        logger.debug("Client connected - auto shutdown canceled")
    }

    private fun scheduleAutoShutdownIfNoClient() {
        autoShutdownRunnable = Runnable {
            if (!hasClientConnected) {
                logger.info("No client connected after 2 minutes. Stopping service.")
                stopSelf()
                hideOverlay()
                onDestroy()
            }
        }
        autoShutdownHandler.postDelayed(autoShutdownRunnable!!, 120_000L)
    }

    private fun cancelAutoShutdown() {
        autoShutdownRunnable?.let {
            autoShutdownHandler.removeCallbacks(it)
        }
    }
}
