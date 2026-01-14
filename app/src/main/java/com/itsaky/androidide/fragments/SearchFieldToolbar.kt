package com.itsaky.androidide.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import com.blankj.utilcode.util.SizeUtils
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.utils.resolveAttr
import com.itsaky.androidide.common.R
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.BaseEditorAction
import com.itsaky.androidide.actions.EditTextAdapter
import com.itsaky.androidide.actions.TextTarget
import com.itsaky.androidide.editor.databinding.LayoutPopupMenuItemBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.R as AndroidRes

class SearchFieldToolbar(private val anchor: EditText) {

    private val context: Context = anchor.context
    private val container = LinearLayout(context)
    private val popupWindow: PopupWindow
    private var actionsAddedCount = 0
    private val textAdapter = EditTextAdapter(anchor)
    private val uiScope = CoroutineScope(Dispatchers.Main)

    companion object {
        const val ID_PASTE = "ide.editor.code.text.paste"
        const val ID_COPY = "ide.editor.code.text.copy"
        const val ID_CUT = "ide.editor.code.text.cut"
        const val ID_SELECT_ALL = "ide.editor.code.text.selectAll"
        const val ID_SHOW_TOOLTIP = "ide.editor.code.text.show_tooltip"
    }

    init {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = SizeUtils.dp2px(28f).toFloat()
        drawable.color = ColorStateList.valueOf(context.resolveAttr(R.attr.colorSurface))
        drawable.setStroke(SizeUtils.dp2px(1f), context.resolveAttr(R.attr.colorOutline))

        container.background = drawable
        container.orientation = LinearLayout.HORIZONTAL
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val verticalPadding = SizeUtils.dp2px(2f)
        val horizontalPadding = SizeUtils.dp2px(8f)
        container.setPaddingRelative(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

        popupWindow = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = SizeUtils.dp2px(8f).toFloat()
            setBackgroundDrawable(0.toDrawable())
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        }
    }

    private fun performAction(action: ActionItem) {
        uiScope.launch {
            val data = ActionData.create(context)
            data.put(TextTarget::class.java, textAdapter)

            if (action is BaseEditorAction) {
                 action.prepare(data)
                 if (action.enabled) {
                     action.execAction(data)
                     if (action.id != ID_SELECT_ALL) {
                         popupWindow.dismiss()
                     }
                 }
            }
        }
    }

    fun show(actions: List<ActionItem>) {
        container.removeAllViews()
        actionsAddedCount = 0
        setupActions(actions)

        if (actionsAddedCount > 0) {
            showPopup()
        }
    }

    private fun setupActions(actions: List<ActionItem>) {
        val hasSelection = anchor.hasSelection()
        val hasText = anchor.text?.isNotEmpty() == true

        val selectAllAction = actions.find { it.id == ID_SELECT_ALL }
        val pasteAction = actions.find { it.id == ID_PASTE }
        val copyAction = actions.find { it.id == ID_COPY }
        val cutAction = actions.find { it.id == ID_CUT }
        val showTooltipAction = actions.find { it.id == ID_SHOW_TOOLTIP }

        if (hasText) {
             addActionToToolbar(selectAllAction)
        }

        addActionToToolbar(pasteAction)

        if (hasSelection) {
            addActionToToolbar(copyAction)
            addActionToToolbar(cutAction)
        }
        addActionToToolbar(showTooltipAction)
    }

    private fun addActionToToolbar(action: ActionItem?) {
        if (action == null) return
        val icon = action.icon?.let { applyTint(it) }
            ?: getFallbackIcon(action.id)

        val tooltip = action.label

        addButton(icon, tooltip) {
            performAction(action)
        }
    }

    private fun addButton(icon: Drawable?, tooltip: String, onClick: () -> Unit) {
        val binding = LayoutPopupMenuItemBinding.inflate(LayoutInflater.from(context), container, false)
        val button = binding.root

        button.text = ""
        button.tooltipText = tooltip
        button.icon = icon
        button.setOnClickListener { onClick() }

        container.addView(button)
        actionsAddedCount++
    }

    private fun getFallbackIcon(id: String): Drawable? {
        val attr = when(id) {
            ID_COPY -> AndroidRes.attr.actionModeCopyDrawable
            ID_PASTE -> AndroidRes.attr.actionModePasteDrawable
            ID_CUT -> AndroidRes.attr.actionModeCutDrawable
            ID_SELECT_ALL -> AndroidRes.attr.actionModeSelectAllDrawable
            ID_SHOW_TOOLTIP -> R.drawable.ic_action_help
            else -> return null
        }
        return getSystemIcon(attr)
    }

    private fun showPopup() {
        container.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))

        val popupWidth = container.measuredWidth
        val popupHeight = container.measuredHeight

        val xOff = (anchor.width / 2) - (popupWidth / 2)
        val yOff = -(anchor.height + popupHeight + SizeUtils.dp2px(8f))

        popupWindow.showAsDropDown(anchor, xOff, yOff)
    }

    private fun getSystemIcon(attrId: Int): Drawable? {
        val arr = context.obtainStyledAttributes(intArrayOf(attrId))
        val drawable = arr.getDrawable(0)
        arr.recycle()
        return applyTint(drawable)
    }

    private fun applyTint(drawable: Drawable?): Drawable? {
        if (drawable != null) {
            val wrapped = DrawableCompat.wrap(drawable).mutate()
            val iconColor = context.resolveAttr(R.attr.colorOnSurface)
            wrapped.alpha = 255
            wrapped.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            return wrapped
        }
        return null
    }
}