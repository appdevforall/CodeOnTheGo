package com.itsaky.androidide.activities.editor

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.appbar.AppBarLayout
import com.itsaky.androidide.databinding.ContentEditorBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Controls immersive behavior for the editor in landscape mode.
 *
 * Top bar:
 * - expands/collapses through AppBarLayout behavior
 * - supports auto-hide after being shown
 * - pauses auto-hide while the user is interacting with the top bar
 *
 * Bottom bar:
 * - remains backed by BottomSheetBehavior
 * - can be visually hidden by translating the collapsed peek area
 */
class LandscapeImmersiveController(
    contentBinding: ContentEditorBinding,
    private val bottomSheetBehavior: BottomSheetBehavior<out View?>,
    private val coroutineScope: CoroutineScope,
) {
    private val topBar = contentBinding.editorAppBarLayout
    private val appBarContent = contentBinding.editorAppbarContent
    private val viewContainer = contentBinding.viewContainer
    private val editorContainer = contentBinding.editorContainer
    private val bottomSheet = contentBinding.bottomSheet
    private val topToggle = contentBinding.btnToggleTopBar
    private val bottomToggle = contentBinding.btnToggleBottomBar

    private var autoHideJob: Job? = null
    private var isBound = false

    private var isLandscape = false
    private var isTopBarRequestedVisible = true
    private var isBottomBarRequestedVisible = true
    private var isBottomBarShown = true
    private var isPendingBottomBarHideAfterCollapse = false
    private var isUserInteractingWithTopBar = false

    private var statusBarTopInset = 0
    private var currentAppBarOffset = 0
    private var lastKnownScrollRange = 0
    private val defaultEditorBottomMargin =
        (editorContainer.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

    private val topBarOffsetListener =
        AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
            currentAppBarOffset = verticalOffset
            lastKnownScrollRange = appBarLayout.totalScrollRange
            updateEditorTopInset()
        }

    private val appBarLayoutChangeListener =
        OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isLandscape) return@OnLayoutChangeListener

            val newScrollRange = topBar.totalScrollRange
            val scrollRangeChanged = newScrollRange != lastKnownScrollRange

            lastKnownScrollRange = newScrollRange

            val isVisuallyOutOfSync = !isTopBarRequestedVisible && currentAppBarOffset > -newScrollRange

            if (scrollRangeChanged || isVisuallyOutOfSync) {
                topBar.post(::enforceCollapsedStateIfNeeded)
            }
        }

    private fun enforceCollapsedStateIfNeeded() {
        if (isTopBarRequestedVisible) return

        collapseTopBarWithoutAnimation()
        updateEditorTopInset()
    }

    private val bottomSheetCallback =
        object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheetView: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED,
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> onBottomBarExpandedOrHalfExpanded()

                    BottomSheetBehavior.STATE_COLLAPSED -> onBottomBarCollapsed()

                    BottomSheetBehavior.STATE_DRAGGING,
                    BottomSheetBehavior.STATE_SETTLING -> Unit

                    BottomSheetBehavior.STATE_HIDDEN -> {
                        isBottomBarShown = false
                    }
                }
            }

            override fun onSlide(bottomSheetView: View, slideOffset: Float) = Unit
        }

    init {
        setupClickListeners()
    }

    private val topBarTouchObserver: (MotionEvent) -> Unit = { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTopBarInteractionStarted()
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> onTopBarInteractionEnded()
        }
    }

    /**
     * Observes mouse hover events to manage the top bar's auto-hide behavior.
     * It pauses the auto-hide timer while the cursor is over the bar (or its buttons),
     * and resumes it when the cursor leaves.
     */
    private val topBarHoverObserver: (MotionEvent) -> Unit = { event ->
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> onTopBarInteractionStarted()

                MotionEvent.ACTION_HOVER_EXIT -> onTopBarInteractionEnded()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind() {
        if (isBound) return
        isBound = true

        topBar.addOnOffsetChangedListener(topBarOffsetListener)
        appBarContent.addOnLayoutChangeListener(appBarLayoutChangeListener)
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
        appBarContent.onTouchEventObserved = topBarTouchObserver
        appBarContent.onHoverEventObserved = topBarHoverObserver
    }

    fun onPause() {
        cancelAutoHide()
        isUserInteractingWithTopBar = false
        cancelBottomSheetAnimation()
        setBottomSheetTranslation(
            if (!isBottomBarShown && bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.peekHeight.toFloat()
            } else {
                0f
            },
        )
    }

    fun onResume() {
        if (isLandscape && isTopBarRequestedVisible && !isUserInteractingWithTopBar) {
            scheduleTopBarAutoHide()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun destroy() {
        onPause()

        if (!isBound) return
        isBound = false

        topToggle.setOnClickListener(null)
        bottomToggle.setOnClickListener(null)

        topBar.removeOnOffsetChangedListener(topBarOffsetListener)
        appBarContent.removeOnLayoutChangeListener(appBarLayoutChangeListener)
        bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        appBarContent.onTouchEventObserved = null
        appBarContent.onHoverEventObserved = null
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        appBarContent.updateLayoutParams<AppBarLayout.LayoutParams> {
            scrollFlags = if (isLandscape) {
                AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
                AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
            } else {
                0
            }
        }

        if (isLandscape) {
            enableImmersiveMode()
        } else {
            disableImmersiveMode()
        }

        updateEditorBottomInset()
    }

    fun onSystemBarInsetsChanged(topInset: Int) {
        statusBarTopInset = topInset
        updateEditorTopInset()
    }

    private fun setupClickListeners() {
        topToggle.setOnClickListener {
            if (!isLandscape) return@setOnClickListener
            if (isTopBarRequestedVisible) hideTopBar() else showTopBar(autoHide = true)
        }

        bottomToggle.setOnClickListener {
            if (!isLandscape) return@setOnClickListener
            if (isBottomBarShown) hideBottomBar() else showBottomBar(expandHalfWay = true)
        }
    }

    private fun onTopBarInteractionStarted() {
        if (!isLandscape || !isTopBarRequestedVisible) return
        isUserInteractingWithTopBar = true
        cancelAutoHide()
    }

    private fun onTopBarInteractionEnded() {
        if (!isLandscape || !isTopBarRequestedVisible) return
        isUserInteractingWithTopBar = false
        scheduleTopBarAutoHide()
    }

    private fun onBottomBarExpandedOrHalfExpanded() {
        cancelBottomSheetAnimation()
        setBottomSheetTranslation(0f)
        isBottomBarShown = true
        isBottomBarRequestedVisible = true
        isPendingBottomBarHideAfterCollapse = false
        updateEditorBottomInset()
    }

    private fun onBottomBarCollapsed() {
        if (isPendingBottomBarHideAfterCollapse && !isBottomBarRequestedVisible) {
            isPendingBottomBarHideAfterCollapse = false
            applyHiddenBottomBarTranslation(animate = true)
            return
        }

        cancelBottomSheetAnimation()
        setBottomSheetTranslation(0f)
        isBottomBarShown = true
        updateEditorBottomInset()
    }

    private fun enableImmersiveMode() {
        setTogglesVisible(true)

        topBar.post { hideTopBar(animate = false) }
        bottomSheet.post { hideBottomBar(animate = false) }
    }

    private fun disableImmersiveMode() {
        cancelAutoHide()
        isUserInteractingWithTopBar = false
        setTogglesVisible(false)

        isTopBarRequestedVisible = true
        topBar.setExpanded(true, false)

        isBottomBarRequestedVisible = true
        isBottomBarShown = true
        isPendingBottomBarHideAfterCollapse = false

        cancelBottomSheetAnimation()
        setBottomSheetTranslation(0f)

        updateEditorBottomInset()
    }

    private fun setTogglesVisible(visible: Boolean) {
        topToggle.isVisible = visible
        bottomToggle.isVisible = visible
    }

    private fun showTopBar(autoHide: Boolean, animate: Boolean = true) {
        cancelAutoHide()
        isTopBarRequestedVisible = true
        topBar.setExpanded(true, animate)
        if (autoHide) scheduleTopBarAutoHide()
    }

    private fun hideTopBar(animate: Boolean = true) {
        cancelAutoHide()
        isUserInteractingWithTopBar = false
        isTopBarRequestedVisible = false
        topBar.setExpanded(false, animate)
    }

    private fun collapseTopBarWithoutAnimation() {
        topBar.setExpanded(false, false)
        currentAppBarOffset = -topBar.totalScrollRange
    }

    private fun scheduleTopBarAutoHide() {
        if (isUserInteractingWithTopBar || !isTopBarRequestedVisible) return

        autoHideJob = coroutineScope.launch {
            delay(TOP_BAR_AUTO_HIDE_DELAY_MS)
            if (!isUserInteractingWithTopBar && isTopBarRequestedVisible) {
                hideTopBar()
            }
        }
    }

    private fun cancelAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = null
    }

    private fun showBottomBar(animate: Boolean = true, expandHalfWay: Boolean = false) {
        isBottomBarRequestedVisible = true
        isBottomBarShown = true
        isPendingBottomBarHideAfterCollapse = false

        updateEditorBottomInset()

        if (expandHalfWay) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        } else { ensureBottomSheetCollapsed() }

        animateBottomSheetTranslation(to = 0f, animate = animate)
    }

    private fun hideBottomBar(animate: Boolean = true) {
        isBottomBarRequestedVisible = false
        isBottomBarShown = false
        updateEditorBottomInset()

        if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
            applyHiddenBottomBarTranslation(animate)
            return
        }

        isPendingBottomBarHideAfterCollapse = true
        ensureBottomSheetCollapsed()
    }

    private fun ensureBottomSheetCollapsed() {
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun applyHiddenBottomBarTranslation(animate: Boolean) {
        animateBottomSheetTranslation(
            to = bottomSheetBehavior.peekHeight.toFloat(),
            animate = animate,
        )
    }

    private fun animateBottomSheetTranslation(to: Float, animate: Boolean) {
        cancelBottomSheetAnimation()
        if (animate) {
            bottomSheet.animate()
                .translationY(to)
                .setDuration(BOTTOM_BAR_ANIMATION_DURATION_MS)
                .start()
        } else {
            setBottomSheetTranslation(to)
        }
    }

    private fun cancelBottomSheetAnimation() {
        bottomSheet.animate().cancel()
    }

    private fun setBottomSheetTranslation(value: Float) {
        bottomSheet.translationY = value
    }

    private fun updateEditorBottomInset() {
        val bottomMargin = if (isLandscape) {
            if (isBottomBarShown) bottomSheetBehavior.peekHeight else 0
        } else {
            defaultEditorBottomMargin
        }

        editorContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (bottomMargin != this.bottomMargin) {
                this.bottomMargin = bottomMargin
            }
        }
    }

    /**
     * Applies the editor top inset progressively as the app bar collapses,
     * avoiding a jump at the end of the animation.
     */
    private fun updateEditorTopInset() {
        val topPadding = when {
            !isLandscape || lastKnownScrollRange <= 0 -> 0
            else -> {
                val collapseFraction =
                    (-currentAppBarOffset.toFloat() / lastKnownScrollRange.toFloat())
                        .coerceIn(0f, 1f)
                (statusBarTopInset * collapseFraction).roundToInt()
            }
        }

        viewContainer.updatePadding(top = topPadding)
    }

    private companion object {
        const val TOP_BAR_AUTO_HIDE_DELAY_MS = 3500L
        const val BOTTOM_BAR_ANIMATION_DURATION_MS = 200L
    }
}
