package com.codeonthego.xkcdrandom

import com.codeonthego.xkcdrandom.fragments.XkcdPanelFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension

/**
 * Random-xkcd demo plugin. Three goals:
 *   1. Show a random xkcd comic in the bottom-sheet "XKCD" tab.
 *   2. Demonstrate the gesture surface — tap / double-tap / triple-tap.
 *   3. Be the canonical "how to write a CoGo plugin" example. Code over
 *      cleverness; comments explain why, not what.
 *
 * The whole plugin is intentionally small. If you're forking this, the
 * reading order is:
 *   - this file: lifecycle + tab registration
 *   - [XkcdPanelFragment]: the bottom-sheet UI + gesture handling
 *   - [com.codeonthego.xkcdrandom.net.XkcdApiClient]: HTTP, single file
 *   - [com.codeonthego.xkcdrandom.ui.TapCountClassifier]: the 1/2/3 tap
 *     state machine, with unit tests
 */
class XkcdRandomPlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "com.codeonthego.xkcdrandom"
        const val TAB_ID = "xkcd_bottom_tab"
    }

    override fun initialize(context: PluginContext): Boolean {
        // initialize() returns Boolean — the IDE skips activate() if this
        // returns false. Wrap in try/catch so a stray exception in our
        // setup can't crash the host IDE.
        return try {
            this.context = context
            context.logger.info("XkcdRandomPlugin initialized")
            true
        } catch (t: Throwable) {
            // Best-effort log even if context wasn't assigned yet.
            context.logger.error("XkcdRandomPlugin initialization failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("XkcdRandomPlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("XkcdRandomPlugin deactivated")
        return true
    }

    override fun dispose() {
        context.logger.info("XkcdRandomPlugin disposed")
    }

    /**
     * Register one bottom-sheet tab. The IDE shows it next to the eight
     * built-in tabs (Build Output, App Logs, …) plus tabs from other
     * plugins. `order` controls our position among plugin tabs only.
     *
     * The fragmentFactory returns a *new* fragment each time the tab is
     * shown — never reuse a single Fragment instance, fragments have
     * lifecycle expectations the IDE manages.
     */
    override fun getEditorTabs(): List<TabItem> = listOf(
        TabItem(
            id = TAB_ID,
            title = "XKCD",
            fragmentFactory = { XkcdPanelFragment() },
            order = 200
        )
    )
}
