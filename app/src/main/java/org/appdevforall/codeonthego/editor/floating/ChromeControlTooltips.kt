
package org.appdevforall.codeonthego.editor.floating

import android.view.View
import org.appdevforall.codeonthego.floating.model.ChromeControl
import org.appdevforall.codeonthego.idetooltips.TooltipCategory
import org.appdevforall.codeonthego.idetooltips.TooltipManager
import org.appdevforall.codeonthego.idetooltips.TooltipTag

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
			ChromeControl.CLOSE -> TooltipTag.WINDOW_CLOSE
		}
}
