package com.itsaky.androidide.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
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

    init {
        LayoutInflater.from(context).inflate(R.layout.custom_toolbar, this, true)
        titleText = findViewById(R.id.title_text)
        menuContainer = findViewById(R.id.menu_container)
        scrollView = findViewById(R.id.horizontal_scroll_view)

    }

    fun setTitleText(title: String) {
        titleText.text = title
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
        menuContainer.addView(item)
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
    }
}