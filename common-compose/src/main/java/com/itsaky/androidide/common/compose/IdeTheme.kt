package com.itsaky.androidide.common.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Wraps Compose content in a [MaterialTheme] whose colours come from the IDE's XML theme, so a Compose
 * surface is indistinguishable from the View-based UI around it.
 *
 * Use this instead of a bare `MaterialTheme { }`: the bare form falls back to Material's purple
 * baseline, which looks nothing like the IDE and ignores the user's theme entirely.
 *
 * [typography] is a parameter because branding type is a separate concern from colour -- overlay
 * windows brand theirs with the IDE's Atkinson Hyperlegible face, while most surfaces want the
 * default.
 */
@Composable
fun IdeTheme(
	typography: Typography = MaterialTheme.typography,
	content: @Composable () -> Unit,
) {
	val context = LocalContext.current
	val dark = isSystemInDarkTheme()
	// Attribute resolution reads the theme, so it is keyed on both the context and the dark-mode flag.
	val colorScheme = remember(context, dark) { context.ideColorScheme(dark) }
	MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
}
