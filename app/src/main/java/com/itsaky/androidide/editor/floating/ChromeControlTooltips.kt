
package com.itsaky.androidide.editor.floating

import android.view.View
import com.itsaky.androidide.floating.model.ChromeControl
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag


object ChromeControlTooltips {
	val handler: (ChromeControl, View) -> Unit = { control, anchor ->
		tagFor(control)?.let { tag ->
			TooltipManager.showTooltip(anchor.context, anchor, TooltipCategory.CATEGORY_IDE, tag)
		}
	}

	private fun tagFor(control: ChromeControl): String? =
		when (control) {
			ChromeControl.MINIMIZE -> TooltipTag.WINDOW_MINIMIZE
			ChromeControl.MAXIMIZE -> TooltipTag.WINDOW_MAXIMIZE
			ChromeControl.DOCK -> TooltipTag.WINDOW_DOCK
			ChromeControl.CLOSE -> TooltipTag.WINDOW_UNDOCK
		}
}
