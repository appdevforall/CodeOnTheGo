package com.itsaky.androidide.fragments.sidebar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R

class GitGraphItemDecoration : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val dotRadius = 10f

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)

        val layoutManager = parent.layoutManager ?: return

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val graphSpace = child.findViewById<View>(R.id.graph_space)

            val cx = graphSpace.left + (graphSpace.width / 2f)
            val cy = child.top + (child.height / 2f)

            // Draw a dot for the commit
            c.drawCircle(cx, cy, dotRadius, paint)

            // VERY SIMPLIFIED: Draw a line to the previous item
            if (i > 0) {
                val prevChild = parent.getChildAt(i - 1)
                val prevCy = prevChild.top + (prevChild.height / 2f)
                c.drawLine(cx, cy, cx, prevCy, paint)
            }
        }
    }
}