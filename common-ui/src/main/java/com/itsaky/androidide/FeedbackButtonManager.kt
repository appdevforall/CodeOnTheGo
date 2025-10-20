package com.itsaky.androidide

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.blankj.utilcode.util.SizeUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.FeedbackManager
import kotlin.math.sqrt

class FeedbackButtonManager(
    val activity: AppCompatActivity,
    val feedbackFab: FloatingActionButton?,
    private val getLogContent: (() -> String?)? = null,
) {
	companion object {
        const val FAB_PREFS = "FabPrefs"
        const val KEY_FAB_X = "fab_x"
        const val KEY_FAB_Y = "fab_y"
	}

	// This function is called in the onCreate method of the activity that contains the FAB
	fun setupDraggableFab() {
        if (feedbackFab != null) {
            loadFabPosition()

            var initialX = 0f
            var initialY = 0f
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false
            var isLongPressed = false

            val gestureDetector =
                GestureDetector(
                    activity,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: MotionEvent) {
                            if (!isDragging) {
                                isLongPressed = true
                                TooltipManager.showTooltip(
                                    context = activity,
                                    anchorView = feedbackFab,
                                    category = TooltipCategory.CATEGORY_IDE,
                                    tag = TooltipTag.FEEDBACK,
                                )
                            }
                        }
                    },
                )

            @SuppressLint("ClickableViewAccessibility")
            feedbackFab.setOnTouchListener { v, event ->
                val parentView = v.parent as? ViewGroup ?: return@setOnTouchListener false

                gestureDetector.onTouchEvent(event)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = v.x
                        initialY = v.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        isLongPressed = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dX = event.rawX - initialTouchX
                        val dY = event.rawY - initialTouchY

                        if (!isDragging &&
                            sqrt((dX * dX + dY * dY).toDouble()) >
                            ViewConfiguration
                                .get(
                                    v.context,
                                ).scaledTouchSlop
                        ) {
                            isDragging = true
                        }

                        if (isDragging) {
                            // Get safe dragging bounds that account for system UI
                            val safeBounds = getSafeDraggingBounds(parentView, v as FloatingActionButton)

                            v.x = (initialX + dX).coerceIn(
                                safeBounds.left.toFloat(),
                                safeBounds.right.toFloat()
                            )
                            v.y = (initialY + dY).coerceIn(
                                safeBounds.top.toFloat(),
                                safeBounds.bottom.toFloat()
                            )
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            saveFabPosition(v.x, v.y)
                        } else if (!isLongPressed) {
                            v.performClick()
                        }
                        true
                    }

                    else -> false
                }
            }

            feedbackFab.setOnClickListener {
                performFeedbackAction()
            }
        }
    }

    private fun performFeedbackAction() {
        val currentLogContent = getLogContent?.invoke()
        FeedbackManager.showFeedbackDialog(
            activity = activity,
            logContent = currentLogContent
        )
    }

	private fun saveFabPosition(
		x: Float,
		y: Float,
	) {
		activity.applicationContext.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE).edit().apply {
			putFloat(KEY_FAB_X, x)
			putFloat(KEY_FAB_Y, y)
			apply()
		}
	}

	/**
	 * Calculate safe bounds for FAB positioning, accounting for system UI elements.
	 * Returns a Rect with the safe dragging area (left, top, right, bottom).
	 */
	private fun getSafeDraggingBounds(parentView: ViewGroup, fabView: FloatingActionButton): Rect {
		val bounds = Rect()

		// Get margin from layout params, or use default 16dp if not available
		val layoutParams = fabView.layoutParams as? ViewGroup.MarginLayoutParams
		val fabMarginPx = layoutParams?.topMargin?.toFloat() ?: SizeUtils.dp2px(16f).toFloat()

		// Get system window insets (status bar, navigation bar, etc.)
		val insets = ViewCompat.getRootWindowInsets(parentView)
		val systemBarsInsets = insets?.getInsets(WindowInsetsCompat.Type.systemBars())

		// Calculate safe minimum Y position
		// Start with system bars top inset (status bar), add a safety margin
		val minY = (systemBarsInsets?.top?.toFloat() ?: 0f) + fabMarginPx

		// Calculate safe bounds
		bounds.left = 0
		bounds.top = minY.toInt()
		bounds.right = (parentView.width - fabView.width).coerceAtLeast(0)
		bounds.bottom = (parentView.height - fabView.height).coerceAtLeast(0)

		return bounds
	}

	/**
	 * Validates if the given position is within safe bounds.
	 * If not, returns a safe default position (bottom-left with margins).
	 */
	private fun validateAndCorrectPosition(x: Float, y: Float, parentView: ViewGroup, fabView: FloatingActionButton): Pair<Float, Float> {
		val safeBounds = getSafeDraggingBounds(parentView, fabView)

		// Check if position is within safe bounds
		val isXValid = x >= safeBounds.left && x <= safeBounds.right
		val isYValid = y >= safeBounds.top && y <= safeBounds.bottom

		return if (isXValid && isYValid) {
			// Position is valid, return as-is
			Pair(x, y)
		} else {
			// Get margins from layout params, or use default 16dp if not available
			val layoutParams = fabView.layoutParams as? ViewGroup.MarginLayoutParams
			val marginStart = layoutParams?.marginStart?.toFloat() ?: SizeUtils.dp2px(16f).toFloat()
			val marginBottom = layoutParams?.bottomMargin?.toFloat() ?: SizeUtils.dp2px(16f).toFloat()

			// Position is invalid, return default position (bottom-left)
			val defaultX = marginStart
			val defaultY = parentView.height - fabView.height - marginBottom
			Pair(defaultX, defaultY)
		}
	}

    // Called in onResume for returning activities to reload FAB position
    fun loadFabPosition() {
        if (feedbackFab != null) {
            val prefs =
                activity.applicationContext.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)

            val x = prefs.getFloat(KEY_FAB_X, -1f)
            val y = prefs.getFloat(KEY_FAB_Y, -1f)

            if (x != -1f && y != -1f) {
                feedbackFab.post {
                    val parentView = feedbackFab.parent as? ViewGroup
                    if (parentView != null) {
                        // Validate and correct the loaded position to ensure it's in a safe area
                        val (validX, validY) = validateAndCorrectPosition(x, y, parentView, feedbackFab)
                        feedbackFab.x = validX
                        feedbackFab.y = validY

                        // If position was corrected, save the new valid position
                        if (validX != x || validY != y) {
                            saveFabPosition(validX, validY)
                        }
                    } else {
                        // Fallback: apply position without validation if parent not available
                        feedbackFab.x = x
                        feedbackFab.y = y
                    }
                }
            }
        }
    }
}
