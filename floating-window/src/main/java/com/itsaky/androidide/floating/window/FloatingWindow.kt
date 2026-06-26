

package com.itsaky.androidide.floating.window

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.itsaky.androidide.floating.model.DockingManager
import com.itsaky.androidide.floating.model.FloatingTab
import com.itsaky.androidide.floating.ui.FloatingTheme
import com.itsaky.androidide.floating.ui.FloatingWindowChrome
import com.itsaky.androidide.floating.ui.MinimizedBubble
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

/**
 * A single live floating overlay window: it binds the Compose chrome to its [tab] content, owns the
 * window's [WindowManager.LayoutParams], and translates chrome gestures into live window updates.
 *
 * Keyboard focus follows interaction: the window is non-focusable (touch passes through to apps
 * behind) until tapped, then becomes focusable so the editor's IME attaches. Minimize/maximize/dock
 * transitions are animated.
 */
class FloatingWindow(
	private val windowContext: Context,
	private val tab: FloatingTab,
) {

	private val windowManager: WindowManager =
		windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
	private val host = FloatingWindowHost()
	private val density = windowContext.resources.displayMetrics.density
	private val minWidthPx = (MIN_WIDTH_DP * density).roundToInt()
	private val minHeightPx = (MIN_HEIGHT_DP * density).roundToInt()
	private val screenWidth = windowContext.resources.displayMetrics.widthPixels
	private val screenHeight = windowContext.resources.displayMetrics.heightPixels
	private val bubbleWidthPx = (BUBBLE_WIDTH_DP * density).roundToInt()
	private val bubbleHeightPx = (BUBBLE_HEIGHT_DP * density).roundToInt()

	private var bounds: WindowBounds = tab.state.bounds
	private var restoreBounds: WindowBounds = tab.state.restoreBounds
	private var focusable: Boolean = false
	private var added: Boolean = false
	private var transition: ValueAnimator? = null

	private val modeState = mutableStateOf(tab.state.mode)
	private val focusedState = mutableStateOf(false)

	private val params: WindowManager.LayoutParams =
		OverlayLayoutParams.create(tab.state, focusable = false)

	private val contentView: View = tab.content.onCreateView(windowContext, host)

	private val composeView: ComposeView = ComposeView(windowContext)

	private val rootView: OverlayRootView =
		OverlayRootView(windowContext).apply {
			addView(
				composeView,
				FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.MATCH_PARENT,
					FrameLayout.LayoutParams.MATCH_PARENT,
				),
			)
		}

	init {
		// Wire callbacks here (not in the OverlayRootView.apply block above): inside that block the
		// receiver is the View, so `setFocusable`/`id` would resolve to View members, not these.
		rootView.onInsideTouch = { setFocusable(true) }
		rootView.isContentTouch = { rawX, rawY -> isWithinContent(rawX, rawY) }
		rootView.onContentTap = { rootView.post { focusContentAndShowIme() } }
		composeView.setContent { this@FloatingWindow.Content() }
	}

	val id: String
		get() = tab.id

	fun show() {
		if (added) return
		host.attach(rootView)
		runCatching { windowManager.addView(rootView, params) }
			.onSuccess { added = true }
			.onFailure { log.error("Failed to add floating window {}", id, it) }
	}

	fun dismiss() {
		transition?.cancel()
		if (added) {
			runCatching { windowManager.removeView(rootView) }
			added = false
		}
		(contentView.parent as? ViewGroup)?.removeView(contentView)
		host.destroy()
		tab.content.onDestroyView()
	}

	private fun moveBy(dx: Float, dy: Float) {
		if (modeState.value == WindowMode.MAXIMIZED) return
		val topInset =
			ViewCompat.getRootWindowInsets(rootView)
				?.getInsets(WindowInsetsCompat.Type.statusBars())
				?.top ?: 0
		val maxX = (screenWidth - params.width).coerceAtLeast(0)
		val maxY = (screenHeight - params.height).coerceAtLeast(topInset)
		params.x = (params.x + dx.roundToInt()).coerceIn(0, maxX)
		params.y = (params.y + dy.roundToInt()).coerceIn(topInset, maxY)
		bounds = bounds.copy(x = params.x, y = params.y)
		if (modeState.value == WindowMode.MINIMIZED) {
			restoreBounds = restoreBounds.copy(x = params.x, y = params.y)
		}
		safeUpdate()
	}

	private fun resizeBy(dw: Float, dh: Float) {
		if (modeState.value != WindowMode.NORMAL) return
		params.width = (params.width + dw.roundToInt()).coerceAtLeast(minWidthPx)
		params.height = (params.height + dh.roundToInt()).coerceAtLeast(minHeightPx)
		bounds = bounds.copy(width = params.width, height = params.height)
		safeUpdate()
	}

	private fun commitBounds() {
		if (modeState.value == WindowMode.NORMAL) {
			DockingManager.updateBounds(id, bounds)
		}
	}

	private fun minimize() {
		if (modeState.value == WindowMode.NORMAL) {
			restoreBounds = bounds
		}
		setFocusable(false)
		modeState.value = WindowMode.MINIMIZED
		DockingManager.setMode(id, WindowMode.MINIMIZED)
		animateBoundsTo(WindowBounds(params.x, params.y, bubbleWidthPx, bubbleHeightPx))
	}

	private fun restore() {
		modeState.value = WindowMode.NORMAL
		DockingManager.setMode(id, WindowMode.NORMAL)
		animateBoundsTo(restoreBounds) { bounds = restoreBounds }
	}

	private fun toggleMaximize() {
		if (modeState.value == WindowMode.MAXIMIZED) {
			restore()
			return
		}
		if (modeState.value == WindowMode.NORMAL) {
			restoreBounds = bounds
		}
		modeState.value = WindowMode.MAXIMIZED
		DockingManager.setMode(id, WindowMode.MAXIMIZED)
		animateBoundsTo(WindowBounds(0, 0, screenWidth, screenHeight))
	}

	private fun applyBounds(target: WindowBounds) {
		bounds = target
		params.x = target.x
		params.y = target.y
		params.width = target.width
		params.height = target.height
		safeUpdate()
	}

	private fun currentResolvedBounds(): WindowBounds {
		val w = if (params.width >= 0) params.width else screenWidth
		val h = if (params.height >= 0) params.height else screenHeight
		return WindowBounds(params.x, params.y, w, h)
	}

	private fun animateBoundsTo(target: WindowBounds, onEnd: () -> Unit = {}) {
		if (!added) {
			applyBounds(target)
			onEnd()
			return
		}
		transition?.cancel()
		val start = currentResolvedBounds()
		transition =
			ValueAnimator.ofFloat(0f, 1f).apply {
				duration = TRANSITION_MS
				interpolator = DecelerateInterpolator()
				addUpdateListener { animator ->
					val fraction = animator.animatedValue as Float
					params.x = lerp(start.x, target.x, fraction)
					params.y = lerp(start.y, target.y, fraction)
					params.width = lerp(start.width, target.width, fraction)
					params.height = lerp(start.height, target.height, fraction)
					safeUpdate()
				}
				addListener(endListener(onEnd))
				start()
			}
	}

	private fun animateExit(onEnd: () -> Unit) {
		if (!added) {
			onEnd()
			return
		}
		transition?.cancel()
		transition =
			ValueAnimator.ofFloat(params.alpha, 0f).apply {
				duration = EXIT_MS
				addUpdateListener { animator ->
					params.alpha = animator.animatedValue as Float
					safeUpdate()
				}
				addListener(endListener(onEnd))
				start()
			}
	}

	private fun lerp(from: Int, to: Int, fraction: Float): Int =
		(from + (to - from) * fraction).roundToInt()

	private fun endListener(onEnd: () -> Unit): AnimatorListenerAdapter =
		object : AnimatorListenerAdapter() {
			private var cancelled = false

			override fun onAnimationCancel(animation: Animator) {
				cancelled = true
			}

			override fun onAnimationEnd(animation: Animator) {
				if (!cancelled) onEnd()
			}
		}

	private fun setFocusable(value: Boolean) {
		if (focusable == value) return
		focusable = value
		focusedState.value = value
		params.flags = OverlayLayoutParams.flagsFor(value)
		safeUpdate()
		if (!value) {
			hideIme()
			rootView.clearFocus()
		}
	}

	private fun hideIme() {
		val imm = windowContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
		imm?.hideSoftInputFromWindow(rootView.windowToken, 0)
	}

	private fun isWithinContent(rawX: Float, rawY: Float): Boolean {
		val location = IntArray(2)
		contentView.getLocationOnScreen(location)
		val inEditor = rawX >= location[0] && rawX <= location[0] + contentView.width &&
			rawY >= location[1] && rawY <= location[1] + contentView.height
		if (!inEditor) return false

		// Exclude the resize-handle corner (bottom-right of the window): it overlays the editor, so
		// without this, grabbing it to resize registers as a tap on the editor and pops the keyboard.
		val handlePx = RESIZE_HANDLE_DP * density
		val rootLocation = IntArray(2)
		rootView.getLocationOnScreen(rootLocation)
		val handleLeft = rootLocation[0] + rootView.width - handlePx
		val handleTop = rootLocation[1] + rootView.height - handlePx
		return rawX < handleLeft || rawY < handleTop
	}

	private fun focusContentAndShowIme() {
		contentView.requestFocus()
		val target = contentView.findFocus() ?: contentView
		val imm = windowContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
		imm?.showSoftInput(target, 0)
	}

	private fun safeUpdate() {
		if (!added) return
		runCatching { windowManager.updateViewLayout(rootView, params) }
			.onFailure { log.error("Failed to update floating window {}", id, it) }
	}

	@Composable
	private fun Content() {
		FloatingTheme {
			Crossfade(
				targetState = modeState.value == WindowMode.MINIMIZED,
				animationSpec = tween(TRANSITION_MS.toInt()),
				label = "floating-window-mode",
			) { minimized ->
				if (minimized) {
					MinimizedBubble(
						title = tab.content.title,
						onRestore = ::restore,
						onDrag = ::moveBy,
						onDragStopped = ::commitBounds,
					)
				} else {
					FloatingWindowChrome(
						title = tab.content.title,
						focused = focusedState.value,
						maximized = modeState.value == WindowMode.MAXIMIZED,
						onDrag = ::moveBy,
						onDragStopped = ::commitBounds,
						onResize = ::resizeBy,
						onResizeStopped = ::commitBounds,
						onMinimize = ::minimize,
						onToggleMaximize = ::toggleMaximize,
						onDock = { animateExit { DockingManager.dock(id) } },
						onClose = { animateExit { DockingManager.close(id) } },
						actions = tab.content.actions,
						content = { EditorContent() },
					)
				}
			}
		}
	}

	@Composable
	private fun EditorContent() {
		AndroidView(
			factory = {
				(contentView.parent as? ViewGroup)?.removeView(contentView)
				contentView
			},
			modifier = Modifier.fillMaxSize(),
		)
	}

	companion object {

		private val log = LoggerFactory.getLogger(FloatingWindow::class.java)
		private const val MIN_WIDTH_DP = 200f
		private const val MIN_HEIGHT_DP = 140f
		private const val RESIZE_HANDLE_DP = 32f
		private const val BUBBLE_WIDTH_DP = 200f
		private const val BUBBLE_HEIGHT_DP = 52f
		private const val TRANSITION_MS = 200L
		private const val EXIT_MS = 150L
	}
}

/**
 * Root view of a floating window. Reports the first touch inside so the host can enable keyboard
 * focus, and whether a touch landed within the content (vs the chrome).
 */
@SuppressLint("ViewConstructor")
internal class OverlayRootView(context: Context) : FrameLayout(context) {

	var onInsideTouch: (() -> Unit)? = null
	var isContentTouch: ((Float, Float) -> Boolean)? = null
	var onContentTap: (() -> Unit)? = null

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		if (event.action == MotionEvent.ACTION_DOWN) {
			onInsideTouch?.invoke()
			if (isContentTouch?.invoke(event.rawX, event.rawY) == true) {
				onContentTap?.invoke()
			}
		}
		return super.dispatchTouchEvent(event)
	}
}
