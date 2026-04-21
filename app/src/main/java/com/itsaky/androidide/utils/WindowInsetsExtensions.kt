package com.itsaky.androidide.utils

import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ContentEditorBinding
import com.blankj.utilcode.util.SizeUtils
import androidx.core.graphics.Insets
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams

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

        view.updatePadding(top = insets.top)
        appbarContent.updatePadding(top = 0)

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

    fun applyInsets(view: View, windowInsets: WindowInsetsCompat) {
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

        view.updatePadding(
            left = initial.left + if (applyLeft) insets.left else 0,
            top = initial.top + if (applyTop) insets.top else 0,
            right = initial.right + if (applyRight) insets.right else 0,
            bottom = initial.bottom + if (applyBottom) insets.bottom else 0
        )
    }

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        applyInsets(view, windowInsets)
        windowInsets
    }

    doOnAttach { view ->
        ViewCompat.requestApplyInsets(view)

        ViewCompat.getRootWindowInsets(view)?.let { rootInsets ->
            applyInsets(view, rootInsets)
        }
    }
}

/**
 * Applies immersive mode insets to editor UI elements that float near system bars.
 *
 * Keeps toggle buttons and bottom sheet aligned with system bars (status/nav).
 */
fun ContentEditorBinding.applyImmersiveModeInsets(systemBars: Insets) {
    val baseMargin = SizeUtils.dp2px(16f)
    val isRtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL

    val startInset = if (isRtl) systemBars.right else systemBars.left

    btnFullscreenToggle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        bottomMargin = baseMargin + systemBars.bottom
        marginStart = baseMargin + startInset
    }

    bottomSheet.updatePadding(top = systemBars.top)
}

/**
 * Anchors the bottom sheet below the app bar in portrait, but allows it to expand
 * fully in landscape to save vertical screen real estate.
 */
fun ContentEditorBinding.applyBottomSheetAnchorForOrientation(orientation: Int) {
    root.post {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            bottomSheet.resetOffsetAnchor()
        } else {
            bottomSheet.setOffsetAnchor(editorAppBarLayout)
        }
    }
}
