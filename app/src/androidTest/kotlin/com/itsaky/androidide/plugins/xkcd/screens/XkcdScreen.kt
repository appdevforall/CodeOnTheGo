/*
 * Page object for the random-xkcd plugin's bottom-sheet tab.
 *
 * The plugin contributes one bottom-sheet tab via `getEditorTabs()` (see
 * XkcdRandomPlugin.kt). This page object surfaces the views the smoke
 * test asserts on:
 *   - the "XKCD" tab text (registered by `TabItem(title = "XKCD", ...)`)
 *   - the panel root + image card + caption from `fragment_xkcd_panel.xml`
 *
 * View IDs come from the plugin's own resources, so KView builders use
 * Espresso `withId` against the plugin's package's R class — these IDs
 * are runtime-resolved through the plugin's resource context, but Espresso
 * matches them by value, not by package, so the integer constants from
 * the plugin module would suffice IF we could compile against it. We
 * can't (plugin is a separate Gradle module not in app's classpath at
 * androidTest time), so we match by `withTagValue` / `withContentDescription`
 * / `withText` where possible. For id-only matchers we use string
 * resource-name lookup against the host's view tree; UiAutomator can pull
 * them by `resourceIdMatches(".*:id/xkcd_root")`.
 */
package com.itsaky.androidide.plugins.xkcd.screens

import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.kaspersky.kaspresso.screens.KScreen

object XkcdScreen : KScreen<XkcdScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    /**
     * Locate the bottom-sheet "XKCD" tab. Match by visible text, since the
     * tab id is host-internal and the title is a stable contract.
     */
    fun isXkcdTabVisible(device: UiDevice): Boolean {
        val byText = device.findObject(UiSelector().text("XKCD"))
        if (byText.waitForExists(2_000) && byText.exists()) return true
        val byDesc = device.findObject(UiSelector().description("XKCD"))
        return byDesc.waitForExists(2_000) && byDesc.exists()
    }

    fun tapXkcdTab(device: UiDevice) {
        val target = listOf(
            UiSelector().text("XKCD"),
            UiSelector().description("XKCD"),
        ).firstNotNullOfOrNull {
            val o = device.findObject(it)
            if (o.waitForExists(3_000) && o.exists()) o else null
        } ?: error("XKCD tab not found in bottom sheet")
        target.click()
        device.waitForIdle()
    }

    /**
     * Assert the xkcd panel root inflated. Uses the plugin's stable view
     * id `xkcd_root` (from `fragment_xkcd_panel.xml`). Matched via
     * `resourceIdMatches` so the host package prefix doesn't have to be
     * known at compile time.
     */
    fun isPanelInflated(device: UiDevice): Boolean {
        val root = device.findObject(UiSelector().resourceIdMatches(".*:id/xkcd_root"))
        if (root.waitForExists(5_000) && root.exists()) return true
        val legend = device.findObject(UiSelector().resourceIdMatches(".*:id/xkcd_legend"))
        return legend.waitForExists(5_000) && legend.exists()
    }

    /** Tap anywhere inside the panel — used by the smoke test to verify the gesture surface. */
    fun tapPanel(device: UiDevice) {
        val root = device.findObject(UiSelector().resourceIdMatches(".*:id/xkcd_root"))
        if (root.exists()) {
            root.click()
            device.waitForIdle()
        }
    }
}
