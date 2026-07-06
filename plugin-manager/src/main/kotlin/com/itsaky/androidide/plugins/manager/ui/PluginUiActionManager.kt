package com.itsaky.androidide.plugins.manager.ui

import android.util.Log
import com.itsaky.androidide.plugins.manager.core.PluginManager

/**
 * Collects the editor-toolbar action ids that enabled `UIExtension` plugins want hidden
 * right now, by unioning each plugin's `getHiddenToolbarActionIds()`.
 *
 * Stateless: it reads live from [PluginManager.getEnabledUIExtensions], so a plugin
 * disabled mid-session stops contributing on the next toolbar rebuild. There is no
 * allow-list — a plugin may request any toolbar action id; ids that are not currently
 * on the toolbar are simply ignored by the caller. Each plugin call is isolated so one
 * misbehaving plugin cannot break the toolbar.
 */
object PluginUiActionManager {

    private const val TAG = "PluginUiActionManager"

    fun getHiddenActionIds(): Set<String> {
        val extensions = PluginManager.getInstance()?.getEnabledUIExtensions() ?: return emptySet()

        val hidden = mutableSetOf<String>()
        for (extension in extensions) {
            runCatching { extension.getHiddenToolbarActionIds() }
                .onSuccess { hidden.addAll(it) }
                .onFailure { e ->
                    Log.w(TAG, "Failed to get hidden toolbar action ids from ${extension.javaClass.simpleName}", e)
                }
        }
        return hidden
    }
}
