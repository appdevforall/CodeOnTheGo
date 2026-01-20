package com.itsaky.androidide.compose.preview.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

class BoundedComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val composeView: ComposeView = ComposeView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    var maxHeightPx: Int = DEFAULT_MAX_HEIGHT_PX
    var explicitHeightPx: Int? = null

    init {
        addView(composeView)
    }

    fun setViewCompositionStrategy(strategy: ViewCompositionStrategy) {
        composeView.setViewCompositionStrategy(strategy)
    }

    fun setContent(content: @Composable () -> Unit) {
        composeView.setContent(content)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        val newHeightSpec = when {
            explicitHeightPx != null -> {
                MeasureSpec.makeMeasureSpec(explicitHeightPx!!, MeasureSpec.EXACTLY)
            }
            heightMode == MeasureSpec.UNSPECIFIED -> {
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            }
            else -> heightMeasureSpec
        }

        super.onMeasure(widthMeasureSpec, newHeightSpec)
    }

    companion object {
        private val DEFAULT_MAX_HEIGHT_PX = (600 *
            android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }
}
