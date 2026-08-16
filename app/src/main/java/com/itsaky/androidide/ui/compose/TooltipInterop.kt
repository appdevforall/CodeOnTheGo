package com.itsaky.androidide.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.itsaky.androidide.idetooltips.TooltipManager

/**
 * Wires the existing long-press help system (`idetooltips`) into a composable. Compose has no
 * native tooltip entry point yet (the bridge is tracked as ADFA-4381) - this reuses
 * [TooltipManager] via interop instead of a one-off popup, anchored to the Compose hierarchy's
 * root [android.view.View] since a `content://`/dialog composable has no Android `View` of its
 * own to anchor a popup on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.longPressTooltip(tag: String): Modifier {
	val context = LocalContext.current
	val anchorView = LocalView.current
	return combinedClickable(
		onClick = {},
		onLongClick = { TooltipManager.showIdeCategoryTooltip(context, anchorView, tag) },
	)
}
