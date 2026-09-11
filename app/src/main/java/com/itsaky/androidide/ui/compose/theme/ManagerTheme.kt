package com.itsaky.androidide.ui.compose.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MatR

private const val UNRESOLVED_COLOR = Int.MIN_VALUE

/**
 * Wraps manager-screen content (plugin/template manager) in a [MaterialTheme] whose colors are
 * read live from the IDE's XML `Theme.AndroidIDE`, so this first Compose screen in `app` stays
 * visually consistent with the surrounding View-based UI, including light/dark and the
 * BlueWave/SunnyGlow theme variants (all of which override the same Material attrs).
 */
@Composable
fun ManagerTheme(content: @Composable () -> Unit) {
	val context = LocalContext.current
	val dark = isSystemInDarkTheme()
	val colorScheme = remember(context, dark) { context.toComposeColorScheme(dark) }
	MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun Context.toComposeColorScheme(dark: Boolean): ColorScheme {
	val base = if (dark) darkColorScheme() else lightColorScheme()

	fun color(
		attr: Int,
		fallback: Color,
	): Color {
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
