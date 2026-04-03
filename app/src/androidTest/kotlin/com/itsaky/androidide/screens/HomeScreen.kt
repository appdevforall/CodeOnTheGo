package com.itsaky.androidide.screens

import android.view.View
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import io.github.kakaocup.kakao.recycler.KRecyclerItem
import io.github.kakaocup.kakao.recycler.KRecyclerView
import io.github.kakaocup.kakao.text.KButton
import io.github.kakaocup.kakao.text.KTextView
import org.hamcrest.Matcher
import org.hamcrest.Matchers

object HomeScreen : KScreen<HomeScreen>() {

	override val layoutId: Int? = null
	override val viewClass: Class<*>? = null

	val rvActions = KRecyclerView(
		builder = { withId(R.id.actions) },
		itemTypeBuilder = { itemType(::ActionItem) },
	)

	val title = KTextView {
		withId(R.id.getStarted)
	}

	class ActionItem(matcher: Matcher<View>) : KRecyclerItem<ActionItem>(matcher) {

		val button = KButton(matcher) { withId(R.id.itemButton) }
	}

	fun TestContext<Unit>.clickCreateProjectHomeScreen() {
		val ctx = device.targetContext
		val pkg = ctx.packageName
		val createProjectLabel = ctx.getString(R.string.create_project)

		step("Click create project") {
			flakySafely(90_000) {
				device.uiDevice.waitForIdle(3000)

				val clicked =
					clickCreateProjectMainActionUiAutomator(device.uiDevice, pkg, createProjectLabel)
				if (!clicked) {
					HomeScreen {
						try {
							title {
								isVisible()
								withText(Matchers.equalToIgnoringCase(ctx.getString(R.string.get_started)))
							}
						} catch (_: Exception) {
						}
						rvActions {
							childWith<ActionItem> {
								withDescendant {
									withText(Matchers.equalToIgnoringCase(createProjectLabel))
								}
							} perform {
								button {
									isDisplayed()
									click()
								}
							}
						}
					}
				}
				device.uiDevice.waitForIdle(2500)
			}
		}
	}
}

/**
 * Main-screen row is a [com.google.android.material.button.MaterialButton] with label from
 * [R.string.create_project]. Kakao recycler [click] on the item is unreliable here; UiAutomator
 * matches visible text across portrait/landscape and both [R.id.actions] / [R.id.actionsRight] lists.
 */
private fun clickCreateProjectMainActionUiAutomator(d: UiDevice, pkg: String, label: String): Boolean {
	val selectors =
		listOf(
			UiSelector().packageName(pkg).text(label),
			UiSelector().packageName(pkg).description(label),
			UiSelector().packageName(pkg).textMatches("(?i).*create.*(new\\s+)?project.*"),
		)
	for (sel in selectors) {
		val node = d.findObject(sel)
		if (node.waitForExists(8000) && node.exists() && node.isEnabled) {
			runCatching { node.click() }
			d.waitForIdle(2000)
			return true
		}
	}
	return false
}
