package com.itsaky.androidide.idetooltips

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.itsaky.androidide.utils.applyLongPressRecursively
import com.itsaky.androidide.utils.forEachViewRecursively
import androidx.appcompat.R as AndroidR

private tailrec fun Context.findActivity(): Activity? =
	when (this) {
		is Activity -> this
		is ContextWrapper -> baseContext?.findActivity()
		else -> null
	}

/**
 * Attaches an IDE category tooltip listener to an [AlertDialog] when any part of the
 * dialog surface is long-pressed.
 *
 * Also configures soft keyboard auto-show/hide if an [EditText] is present in the
 * dialog's custom panel.
 *
 * @param tooltipTag The tag for the tooltip to display.
 * @param context Optional context override (defaults to dialog's context).
 * @return The [AlertDialog] instance for chaining.
 */
@SuppressLint("ClickableViewAccessibility")
fun AlertDialog.attachTooltip(
	tooltipTag: String,
	context: Context = this.context,
): AlertDialog {
	fun showTooltip() {
		val activity = context.findActivity()
		val anchor = this.window?.decorView ?: activity?.window?.decorView ?: return
		TooltipManager.showIdeCategoryTooltip(
			context = activity ?: context,
			anchorView = anchor,
			tag = tooltipTag,
		)
	}

	val onShowActions = mutableListOf<(DialogInterface) -> Unit>()

	onShowActions.add {
		this.window?.decorView?.applyLongPressRecursively(emptyList(), includeEditTexts = false) {
			showTooltip()
			true
		}
	}

	val customPanel: ViewGroup? = this.findViewById(AndroidR.id.customPanel)
	customPanel?.forEachViewRecursively { view ->
		if (view is EditText) {
			onShowActions.add {
				view.requestFocus()
				val imm =
					context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
				imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
			}

			this.window?.decorView?.setOnTouchListener { _, event ->
				if (event.action == MotionEvent.ACTION_DOWN) {
					val outRect = Rect()
					view.getGlobalVisibleRect(outRect)
					if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
						view.clearFocus()
						val inputMethodManager =
							view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
						inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
					}
				}
				false
			}
		}
	}

	this.setOnShowListener { dialog ->
		for (action in onShowActions) {
			action(dialog)
		}
	}

	this.listView?.onItemLongClickListener =
		AdapterView.OnItemLongClickListener { _, _, _, _ ->
			showTooltip()
			true
		}

	return this
}
