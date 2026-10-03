package com.itsaky.androidide.activities.editor

import android.graphics.ColorFilter
import android.graphics.drawable.Drawable

/**
 * Tints and dims a toolbar action's icon, mutating it first.
 *
 * Order matters: `LayerDrawable.mutate()` rebuilds its layers and drops a filter set before it,
 * and tinting an unmutated icon writes onto state shared with every other use of the resource.
 */
internal fun toolbarIcon(
	icon: Drawable?,
	colorFilter: ColorFilter?,
	enabled: Boolean,
): Drawable? =
	icon?.mutate()?.apply {
		this.colorFilter = colorFilter
		alpha = if (enabled) 255 else 76
	}
