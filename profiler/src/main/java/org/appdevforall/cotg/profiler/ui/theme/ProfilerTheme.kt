package org.appdevforall.cotg.profiler.ui.theme

import android.content.Context
import android.util.TypedValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR

@Composable
fun ProfilerTheme(content: @Composable () -> Unit) {
	val context = LocalContext.current
	val darkTheme = isSystemInDarkTheme()
	val colorScheme =
		remember(context, darkTheme) {
			context.toMaterial3ColorScheme(darkTheme)
		}
	MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun Context.toMaterial3ColorScheme(darkTheme: Boolean): ColorScheme {
	val base = if (darkTheme) darkColorScheme() else lightColorScheme()
	return base.copy(
		primary = resolveColor(MaterialR.attr.colorPrimary, base.primary),
		onPrimary = resolveColor(MaterialR.attr.colorOnPrimary, base.onPrimary),
		primaryContainer = resolveColor(MaterialR.attr.colorPrimaryContainer, base.primaryContainer),
		onPrimaryContainer = resolveColor(MaterialR.attr.colorOnPrimaryContainer, base.onPrimaryContainer),
		secondary = resolveColor(MaterialR.attr.colorSecondary, base.secondary),
		onSecondary = resolveColor(MaterialR.attr.colorOnSecondary, base.onSecondary),
		secondaryContainer = resolveColor(MaterialR.attr.colorSecondaryContainer, base.secondaryContainer),
		onSecondaryContainer = resolveColor(MaterialR.attr.colorOnSecondaryContainer, base.onSecondaryContainer),
		tertiary = resolveColor(MaterialR.attr.colorTertiary, base.tertiary),
		onTertiary = resolveColor(MaterialR.attr.colorOnTertiary, base.onTertiary),
		background = resolveColor(android.R.attr.colorBackground, base.background),
		onBackground = resolveColor(MaterialR.attr.colorOnBackground, base.onBackground),
		surface = resolveColor(MaterialR.attr.colorSurface, base.surface),
		onSurface = resolveColor(MaterialR.attr.colorOnSurface, base.onSurface),
		surfaceVariant = resolveColor(MaterialR.attr.colorSurfaceVariant, base.surfaceVariant),
		onSurfaceVariant = resolveColor(MaterialR.attr.colorOnSurfaceVariant, base.onSurfaceVariant),
		outline = resolveColor(MaterialR.attr.colorOutline, base.outline),
		error = resolveColor(MaterialR.attr.colorError, base.error),
		onError = resolveColor(MaterialR.attr.colorOnError, base.onError),
	)
}

private fun Context.resolveColor(
	attr: Int,
	fallback: Color,
): Color {
	val value = TypedValue()
	if (!theme.resolveAttribute(attr, value, true)) return fallback
	val colorInt =
		if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
			value.data
		} else if (value.resourceId != 0) {
			ContextCompat.getColor(this, value.resourceId)
		} else {
			return fallback
		}
	return Color(colorInt)
}
