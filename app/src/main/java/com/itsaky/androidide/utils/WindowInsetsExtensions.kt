package com.itsaky.androidide.utils

import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import com.itsaky.androidide.R

data class InitialPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Gets or stores the view's original padding to prevent infinite accumulation when applying insets.
 *
 * @return The original [InitialPadding].
 */
fun View.getOrStoreInitialPadding(): InitialPadding {
    return (getTag(R.id.tag_initial_padding) as? InitialPadding)
        ?: InitialPadding(paddingLeft, paddingTop, paddingRight, paddingBottom).also {
            setTag(R.id.tag_initial_padding, it)
        }
}

/**
 * Applies top window insets responsively. Hides the AppBar in landscape mode and adjusts [appbarContent].
 * Forces an inset request on attach to prevent drawing behind system bars after activity recreation.
 *
 * @param appbarContent The inner content view to pad in landscape mode.
 */
fun View.applyResponsiveAppBarInsets(appbarContent: View) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            view.updatePadding(top = 0)
            appbarContent.updatePadding(top = insets.top)
        } else {
            view.updatePadding(top = insets.top)
            appbarContent.updatePadding(top = 0)
        }
        windowInsets
    }

    doOnAttach { it.requestApplyInsets() }
}

/**
 * Applies root system window insets as padding, preserving the view's initial padding.
 * Useful for deeply nested views (like DrawerLayouts) where standard inset listeners fail.
 *
 * @param applyLeft Apply left inset.
 * @param applyTop Apply top inset.
 * @param applyRight Apply right inset.
 * @param applyBottom Apply bottom inset.
 */
fun View.applyRootSystemInsetsAsPadding(
    applyLeft: Boolean = false,
    applyTop: Boolean = false,
    applyRight: Boolean = false,
    applyBottom: Boolean = false
) {
    val initial = getOrStoreInitialPadding()

    fun applyNow() {
        val rootInsets = ViewCompat.getRootWindowInsets(this) ?: return
        val insets = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars())

        updatePadding(
            left = initial.left + if (applyLeft) insets.left else 0,
            top = initial.top + if (applyTop) insets.top else 0,
            right = initial.right + if (applyRight) insets.right else 0,
            bottom = initial.bottom + if (applyBottom) insets.bottom else 0
        )
    }

    doOnAttach {
        applyNow()
        if (ViewCompat.getRootWindowInsets(this) == null) {
            post { applyNow() }
        }
    }
}
