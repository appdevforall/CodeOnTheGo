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
        this.post {
            var navButton: ImageButton? = null
            for (i in 0 until this.childCount) {
                val child = this.getChildAt(i)
                if (child is ImageButton && child.contentDescription == this.navigationContentDescription) {
                    navButton = child
                    break
                }
            }

            navButton?.setOnLongClickListener {
                onNavIconLongClick?.invoke()
                true
            }

        }
    }

    private val binding: CustomToolbarBinding =
        CustomToolbarBinding.inflate(LayoutInflater.from(context), this, true)

    fun setTitleText(title: String) {
        binding.titleText.apply {
            text = title
        }
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
            // Set layout params for width and height
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Add margin to the right for spacing between items except for last item
                if (shouldAddMargin) {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.toolbar_item_spacing)
                }
            }
            setOnClickListener { onClick() }
            setOnLongClickListener {
                onLongClick()
                true
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