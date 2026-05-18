package com.itsaky.androidide.screens

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.itsaky.androidide.helper.setAccessibilityEditText
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import io.github.kakaocup.kakao.check.KCheckBox
import io.github.kakaocup.kakao.spinner.KSpinner
import io.github.kakaocup.kakao.spinner.KSpinnerItem
import io.github.kakaocup.kakao.text.KButton

object ProjectSettingsScreen : KScreen<ProjectSettingsScreen>() {

    override val layoutId: Int? = null

    override val viewClass: Class<*>? = null

    val createProjectButton = KButton {
        withText(R.string.create_project)
        withId(R.id.finish)
    }

    val spinner = KSpinner(
        builder = { withHint("Project Language") },
        itemTypeBuilder = { itemType(::KSpinnerItem) }
    )

    val kotlinScriptText = KCheckBox {
        withText(R.string.msg_use_kts)
    }

    fun TestContext<Unit>.selectJavaLanguage() {
        step("Select the java language") {
            ProjectSettingsScreen {
                spinner {
                    isVisible()
                    open()

                    childAt<KSpinnerItem>(0) {
                        isVisible()
                        hasText("Java")
                        click()
                    }
                }
            }
        }
    }

    fun TestContext<Unit>.selectKotlinLanguage() {
        step("Select the kotlin language") {
            flakySafely(30000) {
                openProjectLanguageDropdown()

                val d = device.uiDevice
                val kotlin = d.findObject(UiSelector().text("Kotlin"))
                check(kotlin.waitForExists(5_000)) { "Kotlin language option not found" }
                kotlin.click()
                d.waitForIdle()
            }
        }
    }

    private fun TestContext<Unit>.openProjectLanguageDropdown() {
        val d = device.uiDevice

        val javaValue = d.findObject(UiSelector().text("Java"))
        if (javaValue.waitForExists(5_000)) {
            val bounds = javaValue.visibleBounds
            d.click(bounds.centerX(), bounds.centerY())
            d.waitForIdle()
            if (d.findObject(UiSelector().text("Kotlin")).waitForExists(2_000)) {
                return
            }
        }

        val languageLabel = d.findObject(UiSelector().textMatches("(?i)Project language"))
        if (languageLabel.waitForExists(5_000)) {
            val bounds = languageLabel.visibleBounds
            d.click(d.displayWidth - 80, bounds.centerY())
            d.waitForIdle()
            if (d.findObject(UiSelector().text("Kotlin")).waitForExists(2_000)) {
                return
            }
        }

        error("Project language dropdown did not open")
    }

    fun TestContext<Unit>.clickCreateProjectProjectSettings() {
        step("Click create project on the Settings Page") {
            val createText = device.targetContext.getString(R.string.create_project)
            clickFirstAccessibilityNodeByText(createText)
            device.uiDevice.waitForIdle()
        }
    }

    fun TestContext<Unit>.setProjectName(name: String) {
        step("Set project name to '$name'") {
            val d = device.uiDevice
            val byText = d.findObject(UiSelector().textStartsWith("My Application"))
            check(byText.waitForExists(3_000)) { "Project name field not found" }
            setAccessibilityEditText("My Application", name, "project name")
            d.waitForIdle()
        }
    }

    fun TestContext<Unit>.uncheckKotlinScript() {
        step("Unselect Kotlin Script for Gradle") {
            kotlinScriptText {
                setChecked(false)
            }
        }
    }
}
