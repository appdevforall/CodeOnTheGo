package com.itsaky.androidide.services.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
class DebuggerService : Service() {

    inner class Binder : android.os.Binder() {
        fun getService(): DebuggerService = this@DebuggerService
    }

    private lateinit var overlayManager: DebugOverlayManager
    private val binder = Binder()

    companion object {
        private val logger = LoggerFactory.getLogger(DebuggerService::class.java)
    }

    override fun onCreate() {
        logger.debug("onCreate()")
        super.onCreate()
        this.overlayManager = DebugOverlayManager.create(this)
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