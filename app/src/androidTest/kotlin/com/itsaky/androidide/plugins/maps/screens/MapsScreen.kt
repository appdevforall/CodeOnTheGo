/*
 * Page object for the GIS / Maps plugin (ADFA-2436).
 *
 * The plugin contributes:
 *   - Sidebar "Map Regions" entry (NavigationItem id="gis.sidebar.map_regions")
 *     → routes to host-resolved editor tab id "gis_regions_main_tab"
 *     hosting RegionManagerFragment.
 *   - Two static project templates (registered via IdeTemplateService).
 *
 * Inside the regions panel (`fragment_region_manager.xml`):
 *   - regions list RecyclerView (id="regions_list")
 *   - empty state (id="empty_state")
 *   - "+ Download new region" button (id="btn_download_new")
 *   - bbox picker container (id="picker_container", hidden until tap)
 *
 * Bbox picker layout (`fragment_bbox_picker.xml`) — the bbox-picker
 * fragment is shown after the user taps "+ Download new region":
 *   - region name input (id="edt_name")
 *   - bbox overlay view (id="bbox_overlay")
 *   - estimate banner (id="estimate_tiles", id="estimate_size")
 *   - cancel/save buttons (id="btn_cancel", id="btn_save")
 */
package com.itsaky.androidide.plugins.maps.screens

import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.kaspersky.kaspresso.screens.KScreen

object MapsScreen : KScreen<MapsScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    /** "Map Regions" sidebar entry should appear after plugin load. */
    fun isSidebarEntryVisible(device: UiDevice): Boolean {
        val byText = device.findObject(UiSelector().text("Map Regions"))
        if (byText.waitForExists(3_000) && byText.exists()) return true
        val byDesc = device.findObject(UiSelector().description("Map Regions"))
        return byDesc.waitForExists(2_000) && byDesc.exists()
    }

    fun tapSidebarEntry(device: UiDevice) {
        val target = listOf(
            UiSelector().text("Map Regions"),
            UiSelector().description("Map Regions"),
        ).firstNotNullOfOrNull {
            val o = device.findObject(it)
            if (o.waitForExists(3_000) && o.exists()) o else null
        } ?: error("Map Regions sidebar entry not found")
        target.click()
        device.waitForIdle()
    }

    /**
     * Verify RegionManagerFragment inflated. Match the "+ Download new
     * region" button (a stable view ID); the regions list and empty state
     * are also valid markers but the CTA is always present regardless of
     * cache state.
     *
     * This is the assertion that catches the runtime fix: if the fragment
     * was launched as a plugin Activity instead (the bug fixed in commit
     * `12ca5aa98`), it would have failed with `ActivityNotFoundException`
     * before reaching layout inflation. If the inflater wasn't wrapped via
     * `PluginFragmentHelper.getPluginInflater`, layout inflation would
     * have thrown `Resources$NotFoundException` for any plugin-package
     * drawable / string reference.
     */
    fun isRegionManagerInflated(device: UiDevice): Boolean {
        val downloadBtn = device.findObject(
            UiSelector().resourceIdMatches(".*:id/btn_download_new")
        )
        if (downloadBtn.waitForExists(5_000) && downloadBtn.exists()) return true
        // Fallback to the empty-state container (visible when no regions cached).
        val emptyState = device.findObject(
            UiSelector().resourceIdMatches(".*:id/empty_state")
        )
        if (emptyState.waitForExists(3_000) && emptyState.exists()) return true
        // Last-resort fallback: text match on the CTA.
        val byText = device.findObject(UiSelector().textContains("Download new region"))
        return byText.waitForExists(3_000) && byText.exists()
    }

    fun tapDownloadNewRegion(device: UiDevice) {
        val byId = device.findObject(
            UiSelector().resourceIdMatches(".*:id/btn_download_new")
        )
        if (byId.waitForExists(3_000) && byId.exists()) {
            byId.click()
            device.waitForIdle()
            return
        }
        val byText = device.findObject(UiSelector().textContains("Download new region"))
        if (byText.waitForExists(3_000) && byText.exists()) {
            byText.click()
            device.waitForIdle()
            return
        }
        error("+ Download new region button not found")
    }

    fun isBboxPickerInflated(device: UiDevice): Boolean {
        val overlay = device.findObject(
            UiSelector().resourceIdMatches(".*:id/bbox_overlay")
        )
        if (overlay.waitForExists(5_000) && overlay.exists()) return true
        val nameInput = device.findObject(
            UiSelector().resourceIdMatches(".*:id/edt_name")
        )
        return nameInput.waitForExists(3_000) && nameInput.exists()
    }

    fun pressBack(device: UiDevice) {
        device.pressBack()
        device.waitForIdle()
    }
}
