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

    /**
     * Preserves the legacy API for setting the toolbar title for backward compatibility.
     *
     * This method is a no-op; the toolbar title is managed separately (see the `title_text` TextView
     * in content_editor.xml).
     *
     * @param title The title to set; this value is ignored.
     */
    @Deprecated("Title is now displayed separately. Use the title_text TextView in content_editor.xml instead.")
    fun setTitleText(title: String) {
        // Title is now handled separately in content_editor.xml
        // This method is kept for backward compatibility but does nothing
    }

    /**
     * Adds a circular icon button to the toolbar's menu area.
     *
     * The created button is added to the internal menu container and wired with the provided click and long-click handlers.
     *
     * @param icon The drawable to display in the button, or `null` for no image.
     * @param hint The tooltip text shown for the button.
     * @param onClick Action invoked when the button is clicked.
     * @param onLongClick Action invoked when the button is long-clicked; the long-click event is consumed.
     * @param shouldAddMargin If `true`, adds end margin to the button for spacing between menu items.
     */
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