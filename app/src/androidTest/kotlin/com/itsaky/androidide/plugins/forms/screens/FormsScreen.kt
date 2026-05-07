/*
 * Page object for the Forms plugin (ADFA-2435).
 *
 * The plugin contributes:
 *   - Sidebar "Form schema" entry (NavigationItem id="forms_schema_panel")
 *     → opens SchemaPanelFragment as an editor tab.
 *   - Toolbar "📷 Capture form from photo" action → same editor tab.
 *   - Editor tab id="forms_schema_panel_tab" hosting SchemaPanelFragment.
 *
 * Inside the panel:
 *   - title TextView (forms_panel_title)
 *   - capture button (forms_panel_capture_button) — tap to launch the
 *     wizard hosted inline via childFragmentManager.
 *   - field list (forms_panel_fields RecyclerView).
 *
 * Wizard step 1 layout (fragment_wizard_step1.xml):
 *   - "Take photo" button (forms_wizard_step1_take_photo)
 *   - "Skip" button (forms_wizard_step1_skip)
 *   - app name + package name TextInputEditTexts.
 */
package com.itsaky.androidide.plugins.forms.screens

import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.kaspersky.kaspresso.screens.KScreen

object FormsScreen : KScreen<FormsScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    /** The sidebar entry should display the "Form schema" label after plugin load. */
    fun isSidebarEntryVisible(device: UiDevice): Boolean {
        val byText = device.findObject(UiSelector().text("Form schema"))
        if (byText.waitForExists(3_000) && byText.exists()) return true
        val byDesc = device.findObject(UiSelector().description("Form schema"))
        return byDesc.waitForExists(2_000) && byDesc.exists()
    }

    fun tapSidebarEntry(device: UiDevice) {
        val target = listOf(
            UiSelector().text("Form schema"),
            UiSelector().description("Form schema"),
        ).firstNotNullOfOrNull {
            val o = device.findObject(it)
            if (o.waitForExists(3_000) && o.exists()) o else null
        } ?: error("Form schema sidebar entry not found")
        target.click()
        device.waitForIdle()
    }

    /**
     * Verify the schema panel inflated. Both `forms_panel_title` and
     * `forms_panel_capture_button` are in the panel layout
     * (`fragment_schema_panel_content.xml`); either being visible confirms
     * the fragment's inflater wired up correctly. If the inflater is wrong
     * (the bug fixed in ADFA-2435 commit `58b788f58`), inflation throws
     * `Resources$NotFoundException` and the panel never renders.
     */
    fun isSchemaPanelInflated(device: UiDevice): Boolean {
        val title = device.findObject(
            UiSelector().resourceIdMatches(".*:id/forms_panel_title")
        )
        if (title.waitForExists(5_000) && title.exists()) return true
        val captureBtn = device.findObject(
            UiSelector().resourceIdMatches(".*:id/forms_panel_capture_button")
        )
        return captureBtn.waitForExists(3_000) && captureBtn.exists()
    }

    fun tapCaptureButton(device: UiDevice) {
        val byId = device.findObject(
            UiSelector().resourceIdMatches(".*:id/forms_panel_capture_button")
        )
        if (byId.waitForExists(3_000) && byId.exists()) {
            byId.click()
            device.waitForIdle()
            return
        }
        // Fallback to text match (the button is a MaterialButton with the
        // emoji-prefixed string).
        val byText = device.findObject(UiSelector().textContains("Capture form from photo"))
        if (byText.waitForExists(3_000) && byText.exists()) {
            byText.click()
            device.waitForIdle()
            return
        }
        error("Capture form from photo button not found")
    }

    fun isWizardStep1Inflated(device: UiDevice): Boolean {
        val takePhoto = device.findObject(
            UiSelector().resourceIdMatches(".*:id/forms_wizard_step1_take_photo")
        )
        if (takePhoto.waitForExists(5_000) && takePhoto.exists()) return true
        val skip = device.findObject(
            UiSelector().resourceIdMatches(".*:id/forms_wizard_step1_skip")
        )
        return skip.waitForExists(3_000) && skip.exists()
    }

    /**
     * System-back: cancel the wizard. The panel should re-appear (or at
     * least the wizard step-1 fragment should be torn down).
     */
    fun pressBack(device: UiDevice) {
        device.pressBack()
        device.waitForIdle()
    }
}
