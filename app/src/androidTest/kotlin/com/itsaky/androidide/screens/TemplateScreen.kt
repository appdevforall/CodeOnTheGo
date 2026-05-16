package com.itsaky.androidide.screens

import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiScrollable
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeParentByText
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

object TemplateScreen {

    fun TestContext<Unit>.selectTemplate(templateResId: Int, visibleTextOverride: String? = null) {
        val templateText = visibleTextOverride ?: device.targetContext.getString(templateResId)

        val d = device.uiDevice
        var templateItem = d.findObject(
            UiSelector().resourceIdMatches(".*:id/template_name").text(templateText),
        )
        if (!templateItem.waitForExists(3_000)) {
            runCatching {
                UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(templateText)
            }
            d.waitForIdle()
            templateItem = d.findObject(
                UiSelector().resourceIdMatches(".*:id/template_name").text(templateText),
            )
        }

        check(templateItem.waitForExists(3_000)) {
            "Template '$templateText' not found in template list"
        }
        clickFirstAccessibilityNodeParentByText(templateText, "template '$templateText'")
        d.waitForIdle()
    }
}
