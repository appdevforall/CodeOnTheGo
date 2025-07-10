package com.itsaky.androidide.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.itsaky.androidide.common.R

class CustomToolbar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MaterialToolbar(context, attrs) {

    private val titleText: TextView
    private val menuContainer: LinearLayout
    private val scrollView: HorizontalScrollView
    private lateinit var startIcon: ImageView
    private lateinit var endIcon: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.custom_toolbar, this, true)
        titleText = findViewById(R.id.title_text)
        menuContainer = findViewById(R.id.menu_container)
        scrollView = findViewById(R.id.horizontal_scroll_view)

        setupStartAndEndArrows()
    }

    fun setTitleText(title: String) {
        titleText.text = title
    }

    private fun setupStartAndEndArrows() {
        startIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_double_arrow_left)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 24
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        endIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_double_arrow_right)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        menuContainer.addView(startIcon)
        menuContainer.addView(endIcon)
    }

    fun addMenuItem(icon: Drawable?, hint: String, onClick: () -> Unit) {
        val item = ImageButton(context).apply {
            tooltipText = hint
            setImageDrawable(icon)
            addCircleRipple()
            // Set layout params for width and height
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Add margin to the right for spacing between items
                marginEnd = resources.getDimensionPixelSize(R.dimen.toolbar_item_spacing)
            }
            setOnClickListener { onClick() }
        }
        // Insert menu item before the end icon
        val insertIndex =
            menuContainer.indexOfChild(endIcon).takeIf { it != -1 } ?: menuContainer.childCount
        menuContainer.addView(item, insertIndex)
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
        menuContainer.removeAllViews()
        // Re-add static start and end icons
        if (::startIcon.isInitialized && ::endIcon.isInitialized) {
            menuContainer.addView(startIcon)
            menuContainer.addView(endIcon)
        }
    }
}