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

package com.itsaky.androidide.utils

import android.content.ComponentCallbacks
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.eventbus.events.editor.ColorSchemeInvalidatedEvent
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.syntax.decoration.EditorDecorationRegistry
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges editor decoration providers contributed by enabled plugins into the editor's
 * [EditorDecorationRegistry].
 *
 * Keeps the dependency direction clean: the app depends on both the plugin manager and `common`,
 * so it populates the registry that the low-level editor pipeline reads — the editor never depends
 * on the plugin manager. The bridge is feature-agnostic; it knows nothing about what any provider
 * decorates.
 *
 * Call [refresh] whenever plugins are (re)loaded/enabled/disabled. [init] additionally tracks the
 * day/night theme so decorations repaint with the right colors the moment the theme flips.
 */
object EditorDecorationBridge {

    private val log = LoggerFactory.getLogger(EditorDecorationBridge::class.java)

    private val registered = AtomicBoolean(false)

    /** Last seen UI night-mode bit, so we only react when day/night actually flips. */
    private var lastNightMode = Int.MIN_VALUE

    /**
     * One-time setup: register a configuration listener so a system (or in-app) day/night flip
     * repaints editor decorations with the matching theme immediately — no restart required — then
     * do an initial [refresh]. Safe to call more than once; only the first call registers.
     */
    @JvmStatic
    fun init() {
        // Atomically claim the one-time registration. Only the winning thread registers; if it
        // fails, reset the flag so a later init() can retry.
        if (registered.compareAndSet(false, true)) {
            try {
                val app = BaseApplication.baseInstance
                lastNightMode = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                app.registerComponentCallbacks(object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        if (night != lastNightMode) {
                            lastNightMode = night
                            refresh()
                        }
                    }

                    override fun onLowMemory() {}
                })
            } catch (t: Throwable) {
                registered.set(false)
                log.error("Failed to register editor decoration theme listener", t)
            }
        }
        refresh()
    }

    /**
     * Recompute the active decoration providers and current theme, then force open editors to
     * re-highlight.
     */
    @JvmStatic
    fun refresh() {
        try {
            val providers = PluginManager.getInstance()
                ?.getEnabledEditorDecorationProviders()
                ?: emptyList()

            EditorDecorationRegistry.isDark = isDarkTheme()
            EditorDecorationRegistry.update(providers)
            EventBus.getDefault().post(ColorSchemeInvalidatedEvent())
        } catch (t: Throwable) {
            log.error("Failed to refresh editor decoration providers", t)
        }
    }

    private fun isDarkTheme(): Boolean {
        // Honor an explicit user theme choice immediately (config may not have propagated yet
        // right after a theme toggle); fall back to the resource config for "follow system".
        when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> return true
            AppCompatDelegate.MODE_NIGHT_NO -> return false
        }
        val uiMode = BaseApplication.baseInstance.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
