package io.github.rosemoe.sora.editor.ts.linestyle

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * @author Akash Yadav
 */
class BreakpointDrawable @JvmOverloads constructor(
    orientation: Orientation = Orientation.TOP_BOTTOM,
    colors: IntArray? = null,
) : GradientDrawable(
    orientation, colors
) {
    init {
        shape = OVAL
        setColor(Color.RED)
    }
}