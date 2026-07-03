/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.syntax.decoration

import com.itsaky.androidide.plugins.extensions.EditorDecorationProvider

/**
 * Process-wide holder for the editor decoration providers contributed by enabled plugins.
 *
 * The app populates this from the plugin manager; the editor's tree-sitter span pipeline reads it
 * and merges each provider's spans into the rendered styling. It lives in `common` — a module the
 * editor pipeline already depends on — so the editor stays decoupled from the plugin manager while
 * still being plugin-driven. The IDE side is entirely feature-agnostic: it knows nothing about what
 * any provider decorates.
 */
object EditorDecorationRegistry {

    @Volatile
    private var providers: List<EditorDecorationProvider> = emptyList()

    /** Whether the editor is currently showing a dark theme; passed to each provider. */
    @Volatile
    @JvmField
    var isDark: Boolean = false

    /** Replace the set of active decoration providers (empty disables decoration entirely). */
    @JvmStatic
    fun update(newProviders: List<EditorDecorationProvider>) {
        providers = newProviders
    }

    /** The active decoration providers, or an empty list if none. */
    @JvmStatic
    fun providers(): List<EditorDecorationProvider> = providers
}
