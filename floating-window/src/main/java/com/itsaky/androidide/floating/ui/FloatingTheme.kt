

package com.itsaky.androidide.floating.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MatR
import com.itsaky.androidide.resources.R as ResR

private const val UNRESOLVED_COLOR = Int.MIN_VALUE

private val AtkinsonHyperlegible: FontFamily =
	FontFamily(
		Font(ResR.font.atkinson_hyperlegible_regular, FontWeight.Normal),
		Font(ResR.font.atkinson_hyperlegible_bold, FontWeight.Bold),
		Font(ResR.font.atkinson_hyperlegible_italic, FontWeight.Normal, FontStyle.Italic),
		Font(ResR.font.atkinson_hyperlegible_bold_italic, FontWeight.Bold, FontStyle.Italic),
	)

/**
 * Wraps floating-window content in a [MaterialTheme] whose colors are read live from the IDE's XML
 * `Theme.AndroidIDE` (via the supplied window context) and whose type uses the IDE's Atkinson
 * Hyperlegible face. This keeps overlay windows visually identical to the docked editor, including
 * light/dark.
 */
@Composable
fun FloatingTheme(content: @Composable () -> Unit) {
	val context = LocalContext.current
	val dark = isSystemInDarkTheme()
	val colorScheme = remember(context, dark) { context.toComposeColorScheme(dark) }
	val typography = remember { brandedTypography() }
	MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
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

private fun Context.toComposeColorScheme(dark: Boolean): ColorScheme {
	val base = if (dark) darkColorScheme() else lightColorScheme()

	fun color(attr: Int, fallback: Color): Color {
		val resolved = MaterialColors.getColor(this, attr, UNRESOLVED_COLOR)
		return if (resolved == UNRESOLVED_COLOR) fallback else Color(resolved)
	}

	return base.copy(
		primary = color(MatR.attr.colorPrimary, base.primary),
		onPrimary = color(MatR.attr.colorOnPrimary, base.onPrimary),
		primaryContainer = color(MatR.attr.colorPrimaryContainer, base.primaryContainer),
		onPrimaryContainer = color(MatR.attr.colorOnPrimaryContainer, base.onPrimaryContainer),
		secondary = color(MatR.attr.colorSecondary, base.secondary),
		onSecondary = color(MatR.attr.colorOnSecondary, base.onSecondary),
		surface = color(MatR.attr.colorSurface, base.surface),
		onSurface = color(MatR.attr.colorOnSurface, base.onSurface),
		surfaceVariant = color(MatR.attr.colorSurfaceVariant, base.surfaceVariant),
		onSurfaceVariant = color(MatR.attr.colorOnSurfaceVariant, base.onSurfaceVariant),
		outline = color(MatR.attr.colorOutline, base.outline),
		error = color(MatR.attr.colorError, base.error),
		onError = color(MatR.attr.colorOnError, base.onError),
		background = color(android.R.attr.colorBackground, base.background),
	)
}
