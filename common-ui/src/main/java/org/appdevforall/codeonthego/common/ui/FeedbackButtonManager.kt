package org.appdevforall.codeonthego.common.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.FeedbackManager
import kotlin.math.sqrt

class FeedbackButtonManager(
	val activity: AppCompatActivity,
	val feedbackFab: FloatingActionButton,
) {
	companion object {
		private const val FAB_PREFS = "FabPrefs"
		private const val KEY_FAB_X = "fab_x"
		private const val KEY_FAB_Y = "fab_y"
	}

	fun setupDraggableFab() {
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
						v.x = (initialX + dX).coerceIn(0f, (parentView.width - v.width).toFloat())
						v.y = (initialY + dY).coerceIn(0f, (parentView.height - v.height).toFloat())
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

	private fun performFeedbackAction() {
		FeedbackManager.showFeedbackDialog(
			activity = activity,
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

	private fun loadFabPosition() {
		val prefs = activity.applicationContext.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)

		val x = prefs.getFloat(KEY_FAB_X, -1f)
		val y = prefs.getFloat(KEY_FAB_Y, -1f)

		if (x != -1f && y != -1f) {
			feedbackFab.post {
				feedbackFab.x = x
				feedbackFab.y = y
			}
		}
	}
}
