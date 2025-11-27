
package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
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
}
