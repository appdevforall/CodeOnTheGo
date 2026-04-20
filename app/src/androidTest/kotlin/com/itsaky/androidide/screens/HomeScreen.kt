package com.itsaky.androidide.screens

import android.view.View
import com.itsaky.androidide.R
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import io.github.kakaocup.kakao.recycler.KRecyclerItem
import io.github.kakaocup.kakao.recycler.KRecyclerView
import io.github.kakaocup.kakao.text.KButton
import io.github.kakaocup.kakao.text.KTextView
import org.hamcrest.Matcher

object HomeScreen : KScreen<HomeScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val rvActions = KRecyclerView(
        builder = { withId(R.id.actions) },
        itemTypeBuilder = { itemType(::ActionItem) }
    )

    val title = KTextView {
        withId(R.id.getStarted)
    }

    class ActionItem(matcher: Matcher<View>) : KRecyclerItem<ActionItem>(matcher) {

        val button = KButton(matcher) { withId(R.id.itemButton) }
    }

    fun TestContext<Unit>.clickCreateProjectHomeScreen() {
        step("Click create project") {
            // Use ACTION_CLICK via accessibility — the IDETooltip WebView overlay
            // can intercept coordinate-based clicks from both Espresso and UiAutomator.
            clickFirstAccessibilityNodeByText("Create project")
            device.uiDevice.waitForIdle()
        }
    }
}
