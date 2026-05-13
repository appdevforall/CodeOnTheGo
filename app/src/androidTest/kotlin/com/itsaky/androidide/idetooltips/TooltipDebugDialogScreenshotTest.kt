package com.itsaky.androidide.idetooltips

import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipScreenshotHostActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives a tooltip popup → info dialog and captures a screenshot to
 * /sdcard/test-screenshots/<scenario>.png. The scenario name is passed in
 * via the instrumentation arg `scenario` (defaults to "default").
 *
 * Before invoking, push the test documentation.db onto
 * /sdcard/Download/documentation.db and `touch` it so TooltipManager picks it up
 * over the bundled DOC_DB (TooltipManager only switches if the debug db is newer).
 */
@RunWith(AndroidJUnit4::class)
class TooltipDebugDialogScreenshotTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val scenario: String
        get() = InstrumentationRegistry.getArguments().getString("scenario") ?: "default"

    @Test
    fun capture_tooltipDebugDialog() {
        val scenarioName = scenario

        ActivityScenario.launch(TooltipScreenshotHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val anchor = Button(activity).apply {
                    text = "anchor"
                    contentDescription = "anchor-button"
                }
                activity.setContentView(anchor)
                TooltipManager.showTooltip(
                    context = activity,
                    anchorView = anchor,
                    category = TooltipCategory.CATEGORY_IDE,
                    tag = "smoke",
                )
            }

            // Wait for the info icon to appear inside the tooltip popup.
            val infoIcon = device.wait(Until.findObject(By.desc("Info icon")), 7_000)
            assertNotNull("tooltip popup never showed Info icon", infoIcon)

            // Click via accessibility (coordinate clicks are intercepted by the WebView overlay).
            infoIcon.click()

            // Wait for the debug dialog to render.
            assertTrue(
                "Tooltip Debug Info dialog never appeared",
                device.wait(Until.hasObject(By.text("Tooltip Debug Info")), 7_000),
            )

            // Let the dialog finish drawing before snapping.
            device.waitForIdle(500)

            val outDir = File("/sdcard/test-screenshots").apply { mkdirs() }
            val outFile = File(outDir, "$scenarioName.png")
            outFile.delete()
            val ok = device.takeScreenshot(outFile)
            assertTrue("UiDevice.takeScreenshot failed for ${outFile.absolutePath}", ok)
            assertTrue(
                "screenshot file empty: ${outFile.absolutePath} (${outFile.length()} bytes)",
                outFile.length() > 0,
            )
        }
    }
}
