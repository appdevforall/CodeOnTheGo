package com.itsaky.androidide.services.debug

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.debug.KillVmAction
import com.itsaky.androidide.actions.debug.RestartVmAction
import com.itsaky.androidide.actions.debug.StepIntoAction
import com.itsaky.androidide.actions.debug.StepOutAction
import com.itsaky.androidide.actions.debug.StepOverAction
import com.itsaky.androidide.actions.debug.SuspendResumeVmAction
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.tasks.cancelIfActive
import com.itsaky.androidide.viewmodel.DebuggerConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
class DebuggerService : Service() {
	inner class Binder : android.os.Binder() {
		fun getService(): DebuggerService = this@DebuggerService
	}

	companion object {
		private val logger = LoggerFactory.getLogger(DebuggerService::class.java)
		const val EXTRA_DISPLAY_ID = "debugger.overlay.displayId"
	}

	private val actionsRegistry = ActionsRegistry.getInstance()
	private lateinit var actionsList: List<ActionItem>
	private var overlayManager: DebugOverlayManager? = null
	private val binder = Binder()
	private val serviceScope = CoroutineScope(Dispatchers.Default)

	internal var targetPackage: String? = null

	override fun onCreate() {
		logger.debug("onCreate()")
		super.onCreate()

		val context = this
		actionsList =
			mutableListOf<ActionItem>().apply {
				add(SuspendResumeVmAction(context))
				add(StepOverAction(context))
				add(StepIntoAction(context))
				add(StepOutAction(context))
				add(KillVmAction(context))
				add(RestartVmAction(context))
			}

		this.actionsList.forEach(actionsRegistry::registerAction)

		serviceScope.launch {
			ForegroundAppReceiver.foregroundAppState
				.combine(
					IDEApplication.instance.foregroundActivityState,
				) { foregroundAppState, ourForegroundActivity -> foregroundAppState to ourForegroundActivity }
				.collectLatest { (foregroundAppState, ourForegroundActivity) ->
					withContext(Dispatchers.Main) {
						onForegroundAppChanged(foregroundAppState, ourForegroundActivity)
					}
				}
		}
	}

	private fun onForegroundAppChanged(
		foregroundAppState: ForegroundAppState,
		ourForegroundActivity: Activity? = IDEApplication.instance.foregroundActivity,
	) {
		logger.debug("onForegroundAppChanged(event={})", foregroundAppState)
		val packageNames = foregroundAppState.packageNames

		val isCotg = BuildInfo.PACKAGE_NAME in packageNames
		val isEditorActivityInForeground = ourForegroundActivity is BaseEditorActivity
		logger.debug(
			"isCotg={}, isEditorActivityInForeground={}",
			isCotg,
			isEditorActivityInForeground,
		)

		if ((isCotg && isEditorActivityInForeground) || (targetPackage != null && targetPackage in packageNames)) {
			showOverlay()
		} else {
			hideOverlay()
		}
	}

	override fun onDestroy() {
		logger.debug("onDestroy()")
		targetPackage = null
		serviceScope.cancelIfActive("DebuggerService is being destroyed")

		detachOverlay()
		super.onDestroy()

		actionsList.forEach(actionsRegistry::unregisterAction)
	}

	fun showOverlay() {
		logger.debug("showOverlay()")
		this.overlayManager?.show()
	}

	fun hideOverlay() {
		logger.debug("hideOverlay()")
		this.overlayManager?.hide()
	}

	fun setOverlayVisibility(isShown: Boolean) = if (isShown) showOverlay() else hideOverlay()

	fun maybeMoveOverlayToDisplay(displayId: Int) {
		if (overlayManager?.attachedDisplayId == displayId) return

		hideOverlay()
		createOverlayManagerIfNeeded(displayId)
		showOverlay()
	}

	private fun createOverlayManagerIfNeeded(displayId: Int) {
		if (overlayManager?.attachedDisplayId != displayId) {
			hideOverlay()
			overlayManager = null
		}

		if (overlayManager == null) {
			overlayManager =
				DebugOverlayManager.create(
					ctx = this,
					displayId = displayId
				)
		}
	}

	private fun detachOverlay() {
		try {
			hideOverlay()
		} catch (err: Throwable) {
			logger.error("Failed to hide debugger overlay", err)
		}
	}

	fun onConnectionStateUpdated(newState: DebuggerConnectionState) {
		setOverlayVisibility(newState >= DebuggerConnectionState.ATTACHED)
		overlayManager?.refreshActions()
	}

	override fun onBind(intent: Intent?): IBinder {
		logger.debug("onBind(intent={}): extras={}", intent, intent?.extras)
		createOverlayManagerIfNeeded(displayId = intent?.extras?.getInt(EXTRA_DISPLAY_ID, -1) ?: -1)
		return this.binder
	}

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int,
	): Int {
		logger.debug(
			"onStartCommand(intent={}, flags={}, startId={}): extras={}",
			intent,
			flags,
			startId,
			intent?.extras,
		)
		// if the service is killed by the system, there is no point in restarting it
		return START_NOT_STICKY
	}
}
