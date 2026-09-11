

package com.itsaky.androidide.floating.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.itsaky.androidide.common.compose.IdeTheme
import com.itsaky.androidide.resources.R as ResR

private val AtkinsonHyperlegible: FontFamily =
	FontFamily(
		Font(ResR.font.atkinson_hyperlegible_regular, FontWeight.Normal),
		Font(ResR.font.atkinson_hyperlegible_bold, FontWeight.Bold),
		Font(ResR.font.atkinson_hyperlegible_italic, FontWeight.Normal, FontStyle.Italic),
		Font(ResR.font.atkinson_hyperlegible_bold_italic, FontWeight.Bold, FontStyle.Italic),
	)

/**
 * Wraps floating-window content in the shared [IdeTheme] -- colors read live from the IDE's XML
 * `Theme.AndroidIDE` via the window context -- with type overridden to the IDE's Atkinson Hyperlegible
 * face. This keeps overlay windows visually identical to the docked editor, including light/dark.
 *
 * Only the typography is local to this module; the color mapping is shared so every Compose surface
 * resolves theme attributes the same way.
 */
@Composable
fun FloatingTheme(content: @Composable () -> Unit) {
	val typography = remember { brandedTypography() }
	IdeTheme(typography = typography, content = content)
}

private fun brandedTypography(): Typography {
	val base = Typography()

	fun TextStyle.branded(): TextStyle = copy(fontFamily = AtkinsonHyperlegible)
	return base.copy(
		titleMedium = base.titleMedium.branded(),
		titleSmall = base.titleSmall.branded(),
		bodyMedium = base.bodyMedium.branded(),
		labelLarge = base.labelLarge.branded(),
		labelMedium = base.labelMedium.branded(),
		labelSmall = base.labelSmall.branded(),
	)
}
