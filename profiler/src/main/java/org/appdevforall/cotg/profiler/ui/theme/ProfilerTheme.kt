package org.appdevforall.cotg.profiler.ui.theme

import androidx.compose.runtime.Composable
import com.itsaky.androidide.common.compose.IdeTheme

/**
 * Profiler content themed from the IDE's XML theme.
 *
 * A thin alias for [IdeTheme]: the attribute-to-role mapping this used to carry is shared, so every
 * Compose surface in the app resolves colours the same way.
 */
@Composable
fun ProfilerTheme(content: @Composable () -> Unit) = IdeTheme(content = content)
