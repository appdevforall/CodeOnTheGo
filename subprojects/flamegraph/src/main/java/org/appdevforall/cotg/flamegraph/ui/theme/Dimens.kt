package org.appdevforall.cotg.flamegraph.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Library-local spacing/sizing tokens. Kept internal so the module stays dependency-free. */
internal object Dimens {
	val rowHeight: Dp = 22.dp

	/** Hairline gap between frames; the surface shows through it to separate adjacent frames. */
	val frameGap: Dp = 1.dp

	/** Frames narrower than this are not drawn at all. */
	val minFrameWidth: Dp = 2.dp

	/** Frames narrower than this skip their label (no room to read it). */
	val minLabelWidth: Dp = 28.dp

	val selectedStroke: Dp = 2.dp
	val labelPaddingH: Dp = 4.dp
	val labelSize: TextUnit = 11.sp

	val crumbPaddingH: Dp = 8.dp
	val crumbPaddingV: Dp = 4.dp
	val crumbGap: Dp = 2.dp
}
