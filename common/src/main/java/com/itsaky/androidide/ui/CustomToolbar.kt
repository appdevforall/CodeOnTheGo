package com.itsaky.androidide.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.itsaky.androidide.common.R
import com.itsaky.androidide.common.databinding.CustomToolbarBinding

class CustomToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    var onNavIconLongClick: (() -> Unit)? = null
) : MaterialToolbar(context, attrs) {

    init {
        // Navigation icon is no longer used in CustomToolbar
        // It's now handled by the title toolbar
        // Remove any navigation icon that might be set
        navigationIcon = null
    }

    private val binding: CustomToolbarBinding =
        CustomToolbarBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        // Remove all padding from the root toolbar
        setPadding(0, 0, 0, 0)
        // Remove all padding from the menu container
        binding.menuContainer.setPadding(0, 0, 0, 0)
        // Also set padding on the HorizontalScrollView
        binding.horizontalScrollView.setPadding(0, 0, 0, 0)
    }

    @Deprecated("Title is now displayed separately. Use the title_text TextView in content_editor.xml instead.")
    fun setTitleText(title: String) {
        // Title is now handled separately in content_editor.xml
        // This method is kept for backward compatibility but does nothing
    }

    fun addMenuItem(
        icon: Drawable?,
        hint: String,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        shouldAddMargin: Boolean
    ) {
        val item = ImageButton(context).apply {
            tooltipText = hint
            setImageDrawable(icon)
            addCircleRipple()
            // Remove any default padding from ImageButton
            setPadding(0, 0, 0, 0)
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
            // Prevent DrawerLayout from intercepting touch events on this button
            setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // Request that parent views (like DrawerLayout) don't intercept touch events
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        // Allow parent to intercept again after the touch is done
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false // Don't consume the event, let the click/long-click listeners handle it
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