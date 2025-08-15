package com.itsaky.androidide.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.itsaky.androidide.common.R
import com.itsaky.androidide.common.databinding.CustomToolbarBinding
import com.itsaky.androidide.utils.isSystemInDarkMode

class CustomToolbar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MaterialToolbar(context, attrs) {

    private val binding: CustomToolbarBinding =
        CustomToolbarBinding.inflate(LayoutInflater.from(context), this, true)

    fun setTitleText(title: String) {
        val isDarkMode = this.context.isSystemInDarkMode()
        val textColor = if (isDarkMode) R.color.white else R.color.black

        binding.titleText.apply {
            text = title
            setTextColor(ContextCompat.getColor(context, textColor))
        }
    }

    fun addMenuItem(icon: Drawable?, hint: String, onClick: () -> Unit, shouldAddMargin: Boolean) {
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
}