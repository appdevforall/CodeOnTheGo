package com.itsaky.androidide.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.itsaky.androidide.common.R
import com.itsaky.androidide.common.databinding.ProjectActionsToolbarBinding

class ProjectActionsToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    var onNavIconLongClick: (() -> Unit)? = null
) : MaterialToolbar(context, attrs) {

    companion object {
        private const val TOOLTIP_HOVER_SHOW_DELAY_MS = 600L
    }

    init {
        // Navigation icon is no longer used in ProjectActionsToolbar
        // It's now handled by the title toolbar
        // Remove any navigation icon that might be set
        navigationIcon = null
    }

    private val binding: ProjectActionsToolbarBinding =
        ProjectActionsToolbarBinding.inflate(LayoutInflater.from(context), this, true)

    @Deprecated("Title is now displayed separately. Use the title_text TextView in content_editor.xml instead.")
    fun setTitleText(title: String) = Unit

    fun addMenuItem(
        icon: Drawable?,
        hint: String,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        onHover: ((View) -> Unit)? = null,
        onHoverExit: (() -> Unit)? = null,
        shouldAddMargin: Boolean
    ) {
        val item = ImageButton(context).apply {
            if (onHover == null) {
                tooltipText = hint
            }
            contentDescription = hint
            setImageDrawable(icon)
            addCircleRipple()
            // Set layout params for width and height
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Apply uniform spacing to all buttons for consistent appearance
                // Use a smaller spacing value for tighter button layout
                marginEnd = resources.getDimensionPixelSize(R.dimen.toolbar_item_spacing) / 2
            }
            setOnClickListener { onClick() }
            setOnLongClickListener {
                onLongClick()
                true
            }
            var hoverRunnable: Runnable? = null
            setOnHoverListener { view, event ->
                if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return@setOnHoverListener false

                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> {
                        hoverRunnable?.let { view.removeCallbacks(it) }
                        hoverRunnable = Runnable { onHover?.invoke(view) }
                        view.postDelayed(hoverRunnable, TOOLTIP_HOVER_SHOW_DELAY_MS)
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        hoverRunnable?.let { view.removeCallbacks(it) }
                        onHoverExit?.invoke()
                    }
                }

                false
            }
        }
        binding.menuContainer.addView(item)
    }

    private fun View.addCircleRipple() = with(TypedValue()) {
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            this,
            true
        )
        setBackgroundResource(resourceId)
    }

    fun clearMenu() {
        binding.menuContainer.removeAllViews()
    }

    fun setOnNavIconLongClickListener(listener: (() -> Unit)?) {
        this.onNavIconLongClick = listener
    }
}
