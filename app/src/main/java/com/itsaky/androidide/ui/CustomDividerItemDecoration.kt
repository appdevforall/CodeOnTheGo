package com.itsaky.androidide.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class CustomDividerItemDecoration(context: Context, private val drawableResId: Int) :
    RecyclerView.ItemDecoration() {
    private val divider: Drawable? = ContextCompat.getDrawable(context, drawableResId)

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        divider ?: return

        val left = parent.paddingLeft
        val right = parent.width - parent.paddingRight

        // Loop until the second to last child
        val childCount = parent.childCount
        for (i in 0 until childCount - 1) {
            val child = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val top = child.bottom + params.bottomMargin
            val bottom = top + divider.intrinsicHeight
            divider.setBounds(left, top, right, bottom)
            divider.draw(c)
        }
    }

    override fun getItemOffsets(
        outRect: Rect, view: View,
        parent: RecyclerView, state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        // Only add bottom offset if this is not the last item
        if (position < state.itemCount - 1) {
            outRect.bottom = divider?.intrinsicHeight ?: 0
        } else {
            outRect.bottom = 0
        }
    }
}
