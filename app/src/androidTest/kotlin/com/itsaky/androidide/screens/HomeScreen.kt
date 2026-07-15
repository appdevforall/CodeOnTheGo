package com.itsaky.androidide.screens

import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.R
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.itsaky.androidide.resources.R as ResourcesR
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import io.github.kakaocup.kakao.text.KTextView

object HomeScreen : KScreen<HomeScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val title = KTextView {
        withId(R.id.getStarted)
    }

    fun TestContext<Unit>.clickCreateProjectHomeScreen() {
        step("Click create project") {
            // Use ACTION_CLICK via accessibility — the IDETooltip WebView overlay
            // can intercept coordinate-based clicks from both Espresso and UiAutomator.
            // Resolve the label from resources so the test cannot drift from the UI.
            val createProjectLabel = InstrumentationRegistry.getInstrumentation()
                .targetContext.getString(ResourcesR.string.create_project)
            clickFirstAccessibilityNodeByText(createProjectLabel)
            device.uiDevice.waitForIdle()
        }
    }
}
