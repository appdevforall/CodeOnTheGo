package com.itsaky.androidide.services.debug

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.debug.SuspendResumeVmAction
import com.itsaky.androidide.actions.debug.RestartVmAction
import com.itsaky.androidide.actions.debug.StepIntoAction
import com.itsaky.androidide.actions.debug.StepOutAction
import com.itsaky.androidide.actions.debug.StepOverAction
import com.itsaky.androidide.actions.debug.KillVmAction
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.tasks.cancelIfActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    internal var targetPackage: String? = null

    private var foregroundAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ForegroundDetectionService.ACTION_FOREGROUND_APP_CHANGED) {
                return
            }

            val packageName = intent.getStringExtra(ForegroundDetectionService.EXTRA_PACKAGE_NAME)
            logger.debug("onReceive: packageName={} targetPackage={}", packageName, targetPackage)
            if (packageName == BuildInfo.PACKAGE_NAME || (targetPackage != null && packageName == targetPackage)) {
                showOverlay()
            } else {
                hideOverlay()
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DebuggerService::class.java)
    }

    override fun onCreate() {
        logger.debug("onCreate()")
        super.onCreate()

        val context = this
        actionsList = mutableListOf<ActionItem>().apply {
            add(SuspendResumeVmAction(context))
            add(StepOverAction(context))
            add(StepIntoAction(context))
            add(StepOutAction(context))
            add(KillVmAction(context))
            add(RestartVmAction(context))
        }

        this.actionsList.forEach(actionsRegistry::registerAction)
        this.overlayManager = DebugOverlayManager.create(serviceScope, this)

        ContextCompat.registerReceiver(
            this,
            foregroundAppReceiver,
            IntentFilter(ForegroundDetectionService.ACTION_FOREGROUND_APP_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        logger.debug("onDestroy()")
        targetPackage = null
        serviceScope.cancelIfActive("DebuggerService is being destroyed")

        try {
            overlayManager.hide()
        } catch (err: Throwable) {
            logger.error("Failed to hide debugger overlay", err)
        }

        super.onDestroy()

        unregisterReceiver(foregroundAppReceiver)
        actionsList.forEach(actionsRegistry::unregisterAction)
    }

    fun showOverlay() {
        logger.debug("showOverlay()")
        this.overlayManager.show()
    }

    fun hideOverlay() {
        logger.debug("hideOverlay()")
        this.overlayManager.hide()
    }

    fun setOverlayVisibility(isShown: Boolean) = if (isShown) showOverlay() else hideOverlay()

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