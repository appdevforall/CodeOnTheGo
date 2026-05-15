package com.itsaky.androidide.services.debug

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.hardware.display.DisplayManagerCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.databinding.DebuggerActionsWindowBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.PermissionsHelper
import com.itsaky.androidide.utils.isAtLeastR
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Manages interaction with the actions shown in the debugger overlay window.
 *
 * @author Akash Yadav
 */
class DebugOverlayManager private constructor(
	private val windowManager: WindowManager,
	private val binding: DebuggerActionsWindowBinding,
	val attachedDisplayId: Int,
	private val touchSlop: Int = ViewConfiguration.get(binding.root.context).scaledTouchSlop,
) {
	private var initialWindowX = 0
	private var initialWindowY = 0
	private var initialTouchX = 0f
	private var initialTouchY = 0f
	private var isDragging = false

	init {
		binding.dragHandle.root.isLongClickable = true
		binding.dragHandle.root.icon =
			ContextCompat.getDrawable(binding.root.context, R.drawable.ic_drag_handle)

		binding.dragHandle.root.setOnTouchListener { v, event ->
			when (event.action) {
				MotionEvent.ACTION_DOWN -> {
					initialWindowX = overlayLayoutParams.x
					initialWindowY = overlayLayoutParams.y
					initialTouchX = event.rawX
					initialTouchY = event.rawY
					isDragging = false
					false
				}

				MotionEvent.ACTION_MOVE -> {
					val deltaX = (event.rawX - initialTouchX).toInt()
					val deltaY = (event.rawY - initialTouchY).toInt()
					if (isDragging || abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
						isDragging = true
						v.isPressed = false
						v.parent.requestDisallowInterceptTouchEvent(true)
						overlayLayoutParams.x = initialWindowX + deltaX
						overlayLayoutParams.y = initialWindowY + deltaY
						windowManager.updateViewLayout(binding.root, overlayLayoutParams)
						true
					} else false
				}

				MotionEvent.ACTION_UP -> {
					if (isDragging) {
						isDragging = false
						true
					} else {
						v.performClick()
						false
					}
				}

				MotionEvent.ACTION_CANCEL -> {
					isDragging = false
					false
				}

				else -> false
			}
		}

		binding.dragHandle.root.setOnLongClickListener { view ->
			TooltipManager.showIdeCategoryTooltip(
				context = view.context,
				anchorView = view.rootView,
				tag = TooltipTag.DEBUGGER_ACTION_MOVE
			)
			true
		}
	}

	private var isShown = false
	private val overlayLayoutParams by lazy {
		WindowManager.LayoutParams(
			/* w = */ WindowManager.LayoutParams.WRAP_CONTENT,
			/* h = */ WindowManager.LayoutParams.WRAP_CONTENT,
			/* _type = */ WM_OVERLAY_TYPE,
			/* _flags = */ WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			/* _format = */ PixelFormat.TRANSLUCENT
		).apply {
			gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
		}
	}

	fun show() {
		if (isShown) {
			return
		}

		val ctx = binding.root.context

		if (!Settings.canDrawOverlays(ctx)) {
			logger.warn("Overlay permission denied. Skipping debugger overlay window.")

			val state = PermissionsHelper.getOverlayPermissionState(ctx)
			val message = if (state == PermissionsHelper.OverlayPermissionState.UNSUPPORTED) {
				ctx.getString(R.string.permission_overlay_unsupported_hint)
			} else {
				ctx.getString(R.string.permission_overlay_restricted_settings_hint)
			}
			Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
			return
		}

		try {
			windowManager.addView(binding.root, overlayLayoutParams)
			isShown = true
		} catch (err: Throwable) {
			logger.error("Failed to show debugger overlay window", err)
		}
	}

	fun hide() {
		if (!isShown) {
			return
		}

		try {
			windowManager.removeView(binding.root)
		} catch (err: Throwable) {
			logger.error("Failed to hide debugger overlay window", err)
		} finally {
			isShown = false
		}
	}

	fun refreshActions() {
		// noinspection NotifyDataSetChanged
		binding.actions.adapter?.notifyDataSetChanged()
	}

	companion object {

		private val logger = LoggerFactory.getLogger(DebugOverlayManager::class.java)
		private const val WM_OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

		/** * Create a new [DebugOverlayManager] from the given [Context].
		 *
		 * @param ctx The [Context] to use for creating the [DebugOverlayManager].
		 * @return A new [DebugOverlayManager].
		 */
		fun create(ctx: Context, displayId: Int = -1): DebugOverlayManager {
			val windowContext = run {
				if (displayId == -1) {
					logger.debug("no display id provided. overlay will be shown on default display")
					return@run ctx
				}

				logger.debug("trying to get window context for displayId={}", displayId)
				val displayManager = DisplayManagerCompat.getInstance(ctx)
				val targetDisplay = displayManager.getDisplay(displayId)
				val displayContext =
					if (targetDisplay != null) ctx.createDisplayContext(targetDisplay) else ctx

				if (isAtLeastR()) {
					displayContext.createWindowContext(WM_OVERLAY_TYPE, null)
				} else displayContext
			}

			// IMPORTANT!
			// Wrap the context with a theme, so we could use MaterialButtons!
			val context = ContextThemeWrapper(windowContext, R.style.Theme_AndroidIDE)
			val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
			val inflater = LayoutInflater.from(context)

			// noinspection InflateParams
			val layout = DebuggerActionsWindowBinding.inflate(inflater)

			val actionsRegistry = ActionsRegistry.getInstance()
			val debuggerActions = actionsRegistry.getActions(ActionItem.Location.DEBUGGER_ACTIONS)

			val actions = debuggerActions.values.toList()
			val adapter = DebuggerActionsOverlayAdapter(actions)
			layout.actions.adapter = adapter

			logger.debug("overlay manager created for display {}", displayId)
			return DebugOverlayManager(
				windowManager = windowManager,
				binding = layout,
				attachedDisplayId = displayId,
			)
		}
	}
}