package com.itsaky.androidide.screens

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeParentByText
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

object TemplateScreen {

    fun TestContext<Unit>.selectTemplate(templateResId: Int) {
        val templateText = device.targetContext.getString(templateResId)

        val d = device.uiDevice
        val templateItem = d.findObject(
            UiSelector().resourceIdMatches(".*:id/template_name").text(templateText)
        )
        check(templateItem.waitForExists(3_000)) {
            "Template '$templateText' not found in template list"
        }
        clickFirstAccessibilityNodeParentByText(templateText, "template '$templateText'")
        d.waitForIdle()
    }
}