package com.itsaky.androidide.common.compose

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * Resolves a theme colour attribute, or null when the attribute is not defined.
 *
 * Exists so [ideColorScheme] can be exercised without an Android [Context]: the mapping from Material
 * attributes to Compose colour roles is the part worth testing, and it is pure once resolution is a
 * parameter.
 */
typealias ColorAttrResolver = (attr: Int) -> Color?

/**
 * A Compose [ColorScheme] built from the IDE's XML theme, so Compose UI matches the surrounding
 * View-based IDE exactly -- including the user's light/dark choice and any theme overlay in effect.
 *
 * Every role falls back to the stock Material baseline ([lightColorScheme]/[darkColorScheme]) when its
 * attribute is undefined, so a partial XML theme degrades to sensible colours rather than to
 * transparent or black.
 *
 * [dark] selects the baseline. It is the caller's business rather than something read from the context
 * here, because the attribute values already come from whichever theme is applied; the baseline only
 * matters for roles the theme does not define.
 */
fun ideColorScheme(
	dark: Boolean,
	resolve: ColorAttrResolver,
): ColorScheme {
	val base = if (dark) darkColorScheme() else lightColorScheme()

	fun role(
		attr: Int,
		fallback: Color,
	): Color = resolve(attr) ?: fallback

	return base.copy(
		primary = role(MaterialR.attr.colorPrimary, base.primary),
		onPrimary = role(MaterialR.attr.colorOnPrimary, base.onPrimary),
		primaryContainer = role(MaterialR.attr.colorPrimaryContainer, base.primaryContainer),
		onPrimaryContainer = role(MaterialR.attr.colorOnPrimaryContainer, base.onPrimaryContainer),
		secondary = role(MaterialR.attr.colorSecondary, base.secondary),
		onSecondary = role(MaterialR.attr.colorOnSecondary, base.onSecondary),
		secondaryContainer = role(MaterialR.attr.colorSecondaryContainer, base.secondaryContainer),
		onSecondaryContainer = role(MaterialR.attr.colorOnSecondaryContainer, base.onSecondaryContainer),
		tertiary = role(MaterialR.attr.colorTertiary, base.tertiary),
		onTertiary = role(MaterialR.attr.colorOnTertiary, base.onTertiary),
		tertiaryContainer = role(MaterialR.attr.colorTertiaryContainer, base.tertiaryContainer),
		onTertiaryContainer = role(MaterialR.attr.colorOnTertiaryContainer, base.onTertiaryContainer),
		// colorBackground is a platform attribute, not a Material one.
		background = role(android.R.attr.colorBackground, base.background),
		onBackground = role(MaterialR.attr.colorOnBackground, base.onBackground),
		surface = role(MaterialR.attr.colorSurface, base.surface),
		onSurface = role(MaterialR.attr.colorOnSurface, base.onSurface),
		surfaceVariant = role(MaterialR.attr.colorSurfaceVariant, base.surfaceVariant),
		onSurfaceVariant = role(MaterialR.attr.colorOnSurfaceVariant, base.onSurfaceVariant),
		outline = role(MaterialR.attr.colorOutline, base.outline),
		outlineVariant = role(MaterialR.attr.colorOutlineVariant, base.outlineVariant),
		error = role(MaterialR.attr.colorError, base.error),
		onError = role(MaterialR.attr.colorOnError, base.onError),
		errorContainer = role(MaterialR.attr.colorErrorContainer, base.errorContainer),
		onErrorContainer = role(MaterialR.attr.colorOnErrorContainer, base.onErrorContainer),
	)
}

/** [ideColorScheme] reading the live attribute values off this context's theme. */
fun Context.ideColorScheme(dark: Boolean): ColorScheme = ideColorScheme(dark, materialColorResolver())

/**
 * Resolves through [MaterialColors], which handles both direct colour values and colour-resource
 * references. A sentinel distinguishes "undefined" from a legitimately resolved colour -- returning 0
 * would be indistinguishable from transparent black.
 */
private fun Context.materialColorResolver(): ColorAttrResolver =
	{ attr ->
		val resolved = MaterialColors.getColor(this, attr, UNRESOLVED)
		if (resolved == UNRESOLVED) null else Color(resolved)
	}

private const val UNRESOLVED = Int.MIN_VALUE
