package com.itsaky.androidide

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.FeedbackManager
import kotlinx.coroutines.launch

/**
 * Handles a draggable FAB with position persistence.
 *
 * Uses normalized ratios instead of absolute coordinates to keep the FAB correctly
 * positioned across layout size changes (e.g. resizing, multi-window, DeX).
 */
class FeedbackButtonManager(
    private val activity: AppCompatActivity,
    private val feedbackFab: FloatingActionButton?,
    private val getLogContent: (() -> String?)? = null,
) {
    private val repository = FabPositionRepository(activity.applicationContext)
    private val calculator = FabPositionCalculator()

    // This function is called in the onCreate method of the activity that contains the FAB
    fun setupDraggableFab() {
        val fab = feedbackFab ?: return
        loadFabPosition()
        setupLayoutChangeListener(fab)
        setupTouchAndClickListeners(fab)
    }

    // Called in onResume for returning activities to reload FAB position
    fun loadFabPosition() {
        val fab = feedbackFab ?: return
        activity.lifecycleScope.launch {
            val (xRatio, yRatio) = repository.readPositionRatios()
            if (xRatio == -1f || yRatio == -1f) return@launch

            fab.post { applySavedPosition(fab, xRatio, yRatio) }
        }
    }

    private fun applySavedPosition(fab: FloatingActionButton, xRatio: Float, yRatio: Float) {
        val parentView = fab.parent as? ViewGroup ?: return
        val safeBounds = calculator.getSafeDraggingBounds(parentView, fab)
        val availableWidth = (safeBounds.right - safeBounds.left).toFloat()
        val availableHeight = (safeBounds.bottom - safeBounds.top).toFloat()

        val x = calculator.fromRatio(xRatio, safeBounds.left, availableWidth)
        val y = calculator.fromRatio(yRatio, safeBounds.top, availableHeight)
        val (validX, validY) = calculator.validateAndCorrectPosition(x, y, parentView, fab)

        fab.x = validX
        fab.y = validY

        if (validX != x || validY != y) {
            saveFabPosition(fab, validX, validY)
        }
    }

    private fun setupLayoutChangeListener(fab: FloatingActionButton) {
        fab.post {
            val parentView = fab.parent as? ViewGroup ?: return@post

            parentView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val newWidth = right - left
                val newHeight = bottom - top
                val oldWidth = oldRight - oldLeft
                val oldHeight = oldBottom - oldTop
                if (newWidth != oldWidth || newHeight != oldHeight) {
                    loadFabPosition()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndClickListeners(fab: FloatingActionButton) {
        val touchListener = DraggableTouchListener(
            context = activity,
            calculator = calculator,
            onSavePosition = { x, y -> saveFabPosition(fab, x, y) },
            onShowTooltip = { showTooltip(fab) }
        )

        fab.setOnTouchListener(touchListener)
        fab.setOnClickListener { performFeedbackAction() }
    }

    private fun saveFabPosition(fab: FloatingActionButton, x: Float, y: Float) {
        val parentView = fab.parent as? ViewGroup ?: return
        // Get safe dragging bounds that account for system UI
        val safeBounds = calculator.getSafeDraggingBounds(parentView, fab)
        val availableWidth = (safeBounds.right - safeBounds.left).toFloat()
        val availableHeight = (safeBounds.bottom - safeBounds.top).toFloat()

        val xRatio = calculator.toRatio(x, safeBounds.left, availableWidth)
        val yRatio = calculator.toRatio(y, safeBounds.top, availableHeight)

        repository.savePositionRatios(xRatio, yRatio)
    }

    private fun showTooltip(fab: FloatingActionButton) {
        TooltipManager.showIdeCategoryTooltip(
            context = activity,
            anchorView = fab,
            tag = TooltipTag.FEEDBACK,
        )
    }

    private fun performFeedbackAction() {
        FeedbackManager.showFeedbackDialog(
            activity = activity,
            logContent = getLogContent?.invoke()
        )
    }
}
