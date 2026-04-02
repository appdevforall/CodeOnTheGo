package com.itsaky.androidide.activities.editor

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ContentEditorBinding
import kotlin.math.abs

class FullscreenController(
    private val contentBinding: ContentEditorBinding,
    private val bottomSheetBehavior: BottomSheetBehavior<out View?>,
    private val closeDrawerAction: () -> Unit,
    private val onFullscreenToggleRequested: () -> Unit,
) {
    private val topBar = contentBinding.editorAppBarLayout
    private val appBarContent = contentBinding.editorAppbarContent
    private val editorContainer = contentBinding.editorContainer
    private val fullscreenToggle = contentBinding.btnFullscreenToggle

    private var isBound = false
    private var isTransitioning = false
    private var currentFullscreen = false
    private var defaultSkipCollapsed = false

    private val transitionDurationMs = 350L

    private val defaultEditorBottomMargin by lazy {
        (editorContainer.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
    }

    private val offsetListener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
        val totalScrollRange = appBarLayout.totalScrollRange
        if (totalScrollRange > 0) {
            val collapseFraction = abs(verticalOffset).toFloat() / totalScrollRange.toFloat()
            appBarContent.alpha = 1f - collapseFraction
        }
    }

    private val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheetView: View, newState: Int) {
            handleBottomSheetStateChange(newState)
        }

        override fun onSlide(bottomSheetView: View, slideOffset: Float) = Unit
    }

    fun bind() {
        if (isBound) return
        isBound = true

        defaultSkipCollapsed = bottomSheetBehavior.skipCollapsed
        bottomSheetBehavior.skipCollapsed = false
        setupScrollFlags()
        topBar.addOnOffsetChangedListener(offsetListener)
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
        fullscreenToggle.setOnClickListener { onFullscreenToggleRequested() }
    }

    fun destroy() {
        if (!isBound) return
        isBound = false

        fullscreenToggle.setOnClickListener(null)
        topBar.removeOnOffsetChangedListener(offsetListener)
        bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        bottomSheetBehavior.skipCollapsed = defaultSkipCollapsed
        fullscreenToggle.removeCallbacks(clearTransitioningRunnable)
    }

    fun render(isFullscreen: Boolean, animate: Boolean) {
        val stateChanged = currentFullscreen != isFullscreen
        val shouldAnimate = animate && stateChanged
        currentFullscreen = isFullscreen
        isTransitioning = shouldAnimate

        if (isFullscreen) {
            applyFullscreen(shouldAnimate)
        } else {
            applyNonFullscreen(shouldAnimate)
        }

        syncToggleUi(isFullscreen)

        if (shouldAnimate) {
            fullscreenToggle.removeCallbacks(clearTransitioningRunnable)
            fullscreenToggle.postDelayed(clearTransitioningRunnable, transitionDurationMs)
        } else {
            isTransitioning = false
        }
    }

    private fun setupScrollFlags() {
        appBarContent.updateLayoutParams<AppBarLayout.LayoutParams> {
            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
                AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
        }
    }

    private fun handleBottomSheetStateChange(newState: Int) {
        if (newState == BottomSheetBehavior.STATE_COLLAPSED && !currentFullscreen) {
            bottomSheetBehavior.isHideable = false
        }

        if (newState == BottomSheetBehavior.STATE_EXPANDED ||
            newState == BottomSheetBehavior.STATE_HALF_EXPANDED
        ) {
            if (currentFullscreen && !isTransitioning) {
                onFullscreenToggleRequested()
            }
        }
    }

    private fun applyFullscreen(animate: Boolean) {
        closeDrawerAction()

        topBar.setExpanded(false, animate)
        appBarContent.alpha = 0f

        bottomSheetBehavior.isHideable = true
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        editorContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = 0
        }
    }

    private fun applyNonFullscreen(animate: Boolean) {
        topBar.setExpanded(true, animate)
        appBarContent.alpha = 1f

        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        } else {
            bottomSheetBehavior.isHideable = false
        }

        editorContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = defaultEditorBottomMargin
        }
    }

    private fun syncToggleUi(isFullscreen: Boolean) {
        if (isFullscreen) {
            fullscreenToggle.setImageResource(R.drawable.ic_fullscreen_exit)
            fullscreenToggle.contentDescription =
                contentBinding.root.context.getString(R.string.desc_exit_fullscreen)
        } else {
            fullscreenToggle.setImageResource(R.drawable.ic_fullscreen)
            fullscreenToggle.contentDescription =
                contentBinding.root.context.getString(R.string.desc_enter_fullscreen)
        }
    }

    private val clearTransitioningRunnable = Runnable {
        isTransitioning = false
    }
}
