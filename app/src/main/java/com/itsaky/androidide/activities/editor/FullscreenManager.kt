package com.itsaky.androidide.activities.editor

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ContentEditorBinding
import kotlin.math.abs

class FullscreenManager(
    private val contentBinding: ContentEditorBinding,
    private val bottomSheetBehavior: BottomSheetBehavior<out View?>,
    private val closeDrawerAction: () -> Unit,
    private val onFullscreenToggleRequested: () -> Unit,
) {
    private sealed interface FullscreenUiState {
        val isFullscreen: Boolean

        data object Fullscreen : FullscreenUiState {
            override val isFullscreen = true
        }

        data object Windowed : FullscreenUiState {
            override val isFullscreen = false
        }

        companion object {
            fun from(isFullscreen: Boolean): FullscreenUiState {
                return if (isFullscreen) Fullscreen else Windowed
            }
        }
    }

    private sealed interface FullscreenRenderCommand {
        val targetState: FullscreenUiState
        val animate: Boolean

        fun apply(manager: FullscreenManager)

        data class EnterFullscreen(
            override val animate: Boolean,
        ) : FullscreenRenderCommand {
            override val targetState = FullscreenUiState.Fullscreen

            override fun apply(manager: FullscreenManager) {
                manager.applyFullscreen(animate)
            }
        }

        data class ExitFullscreen(
            override val animate: Boolean,
        ) : FullscreenRenderCommand {
            override val targetState = FullscreenUiState.Windowed

            override fun apply(manager: FullscreenManager) {
                manager.applyNonFullscreen(animate)
            }
        }

        data class Refresh(
            override val targetState: FullscreenUiState,
        ) : FullscreenRenderCommand {
            override val animate = false

            override fun apply(manager: FullscreenManager) {
                if (targetState.isFullscreen) {
                    manager.applyFullscreen(animate = false)
                } else {
                    manager.applyNonFullscreen(animate = false)
                }
            }
        }

        companion object {
            fun resolve(
                currentState: FullscreenUiState,
                targetState: FullscreenUiState,
                animate: Boolean,
            ): FullscreenRenderCommand {
                val shouldAnimate = animate && currentState != targetState

                if (!shouldAnimate) {
                    return Refresh(targetState)
                }

                return if (targetState.isFullscreen) {
                    EnterFullscreen(animate = true)
                } else {
                    ExitFullscreen(animate = true)
                }
            }
        }
    }

    private val topBar = contentBinding.editorAppBarLayout
    private val appBarContent = contentBinding.editorAppbarContent
    private val editorContainer = contentBinding.editorContainer
    private val fullscreenToggle = contentBinding.btnFullscreenToggle

    private var isBound = false
    private var isTransitioning = false
    private var currentState: FullscreenUiState = FullscreenUiState.Windowed
    private var defaultSkipCollapsed = false
    private var transitionToken = 0L
    private var pendingTransitionToken = 0L

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

        defaultSkipCollapsed = bottomSheetBehavior.skipCollapsed
        bottomSheetBehavior.skipCollapsed = false
        setupScrollFlags()
        topBar.addOnOffsetChangedListener(offsetListener)
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
        fullscreenToggle.setOnClickListener { onFullscreenToggleRequested() }

        isBound = true
    }

    fun destroy() {
        if (!isBound) return

        fullscreenToggle.setOnClickListener(null)
        topBar.removeOnOffsetChangedListener(offsetListener)
        bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        bottomSheetBehavior.skipCollapsed = defaultSkipCollapsed
        fullscreenToggle.removeCallbacks(clearTransitioningRunnable)
        isTransitioning = false

        isBound = false
    }

    fun render(isFullscreen: Boolean, animate: Boolean) {
        val targetState = FullscreenUiState.from(isFullscreen)
        val command =
            FullscreenRenderCommand.resolve(
                currentState = currentState,
                targetState = targetState,
                animate = animate,
            )

        currentState = command.targetState
        syncTransitionState(command)
        command.apply(this)
        syncToggleUi(command.targetState)
    }

    private fun setupScrollFlags() {
        appBarContent.updateLayoutParams<AppBarLayout.LayoutParams> {
            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
                AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
        }
    }

    private fun handleBottomSheetStateChange(newState: Int) {
        val isCollapsedInWindowedMode =
            newState == BottomSheetBehavior.STATE_COLLAPSED && !currentState.isFullscreen
        val isSheetRevealedWhileFullscreen =
            (newState == BottomSheetBehavior.STATE_EXPANDED ||
                newState == BottomSheetBehavior.STATE_HALF_EXPANDED) &&
                currentState.isFullscreen &&
                !isTransitioning

        if (isCollapsedInWindowedMode) {
            bottomSheetBehavior.isHideable = false
        }

        if (isSheetRevealedWhileFullscreen) {
            onFullscreenToggleRequested()
        }
    }

    private fun syncTransitionState(command: FullscreenRenderCommand) {
        fullscreenToggle.removeCallbacks(clearTransitioningRunnable)

        if (!command.animate) {
            isTransitioning = false
            transitionToken++
            return
        }

        isTransitioning = true
        pendingTransitionToken = ++transitionToken
        fullscreenToggle.postDelayed(clearTransitioningRunnable, transitionDurationMs)
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

    private fun syncToggleUi(state: FullscreenUiState) {
        if (state.isFullscreen) {
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
        if (pendingTransitionToken == transitionToken) {
            isTransitioning = false
        }
    }
}
