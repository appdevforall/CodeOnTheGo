package com.itsaky.androidide.ui.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.itsaky.androidide.common.compose.ideColorScheme

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
	val colorScheme = remember(context, dark) { context.ideColorScheme(dark) }
	MaterialTheme(colorScheme = colorScheme, content = content)
}
