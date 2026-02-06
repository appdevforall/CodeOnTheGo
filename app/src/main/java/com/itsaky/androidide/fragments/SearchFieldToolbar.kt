package com.itsaky.androidide.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.graphics.drawable.toDrawable
import com.blankj.utilcode.util.SizeUtils
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.utils.resolveAttr
import com.itsaky.androidide.common.R
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.BaseEditorAction
import com.itsaky.androidide.actions.EditTextAdapter
import com.itsaky.androidide.actions.MutableTextTarget
import com.itsaky.androidide.actions.editor.CopyAction
import com.itsaky.androidide.actions.editor.CutAction
import com.itsaky.androidide.actions.editor.PasteAction
import com.itsaky.androidide.actions.editor.SelectAllAction
import com.itsaky.androidide.actions.file.ShowTooltipAction
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.editor.databinding.LayoutPopupMenuItemBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SearchFieldToolbar(private val anchor: EditText) {

    private val context: Context = anchor.context
    private val container = LinearLayout(context)
    private val popupWindow: PopupWindow
    private val textAdapter = EditTextAdapter(anchor)
    private var uiScope: CoroutineScope? = null
    private var currentActions: List<ActionItem> = emptyList()

    companion object {
        private val KEEP_OPEN_ACTIONS = setOf(SelectAllAction.ID, PasteAction.ID)
    }

    private val allowedActionIds = listOf(
        SelectAllAction.ID,
        CutAction.ID,
        CopyAction.ID,
        PasteAction.ID,
        ShowTooltipAction.ID
    )

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
            false
        ).apply {
            elevation = SizeUtils.dp2px(8f).toFloat()
            setBackgroundDrawable(0.toDrawable())
            isOutsideTouchable = true
            isFocusable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            setOnDismissListener {
                uiScope?.cancel()
                uiScope = null
            }
        }
    }

    private fun performAction(action: ActionItem) {
        uiScope?.launch {
            val data = ActionData.create(context).apply {
                put(MutableTextTarget::class.java, textAdapter)
            }

            (ActionsRegistry.getInstance() as? DefaultActionsRegistry)?.executeAction(action, data)

            when (action.id) {
                in KEEP_OPEN_ACTIONS -> show()
                else -> popupWindow.dismiss()
            }
        }
    }

    fun show() {
        val registry = ActionsRegistry.getInstance()
        val allTextActions = registry.getActions(ActionItem.Location.EDITOR_TEXT_ACTIONS)
        val actionsToShow = allowedActionIds.mapNotNull { id ->
            allTextActions[id]
        }

        if (actionsToShow.isEmpty()) {
            popupWindow.dismiss()
            return
        }

        this.currentActions = actionsToShow

        uiScope?.cancel()
        uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        container.removeAllViews()
        val addedCount = setupActions(actionsToShow)

        if (addedCount == 0) {
            popupWindow.dismiss()
            return
        }

        if (!popupWindow.isShowing) {
            showPopup()
        } else { popupWindow.update() }
    }

    private fun setupActions(actions: List<ActionItem>): Int {
        val data = ActionData.create(context)
        data.put(MutableTextTarget::class.java, textAdapter)

        val visibleActions = actions
            .onEach { (it as? BaseEditorAction)?.prepare(data) }
            .filter { it.visible }

        visibleActions.forEach(::addActionToToolbar)
        return visibleActions.size
    }

    private fun addActionToToolbar(action: ActionItem) {
        val icon = action.icon

        val tooltip = action.label

        addButton(icon, tooltip, action.enabled) {
            performAction(action)
        }
    }

    private fun addButton(icon: Drawable?, tooltip: String, isEnabled: Boolean, onClick: () -> Unit) {
        val binding = LayoutPopupMenuItemBinding.inflate(LayoutInflater.from(context), container, false)
        val button = binding.root

        button.text = ""
        button.tooltipText = tooltip
        button.icon = icon

        button.isEnabled = isEnabled
        button.alpha = if (isEnabled) 1.0f else 0.4f

        button.setOnClickListener { onClick() }

        container.addView(button)
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
}