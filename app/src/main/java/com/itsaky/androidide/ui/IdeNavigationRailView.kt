package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView
import com.google.android.material.navigationrail.NavigationRailView

class IdeNavigationRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.navigationRailStyle
) : NavigationRailView(context, attrs, defStyleAttr) {

    companion object {
        const val MAX_ITEM_COUNT = 12
    }

    override fun getMaxItemCount(): Int = MAX_ITEM_COUNT

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        enableMenuScrolling()
    }

    private fun enableMenuScrolling() {
        post {
            val menuView = getChildAt(0) ?: return@post

            if (menuView is NestedScrollView) return@post

            removeView(menuView)

            val scroll = NestedScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(
                    menuView,
                    LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                    )
                )
            }

            addView(
                scroll,
                LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT
                )
            )
        }
    }
}
