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

private const val KOTLIN_LANGUAGE_SELECTION_TIMEOUT_MS = 30_000L
private const val LANGUAGE_OPTION_TIMEOUT_MS = 5_000L
private const val LANGUAGE_DROPDOWN_EXPANSION_TIMEOUT_MS = 2_000L
private const val PROJECT_NAME_FIELD_TIMEOUT_MS = 3_000L
private const val LANGUAGE_DROPDOWN_FALLBACK_X_OFFSET = 80

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
            val javaText = device.targetContext.getString(R.string.lang_java)
            ProjectSettingsScreen {
                spinner {
                    isVisible()
                    open()

                    childAt<KSpinnerItem>(0) {
                        isVisible()
                        hasText(javaText)
                        click()
                    }
                }
            }
        }
    }

    fun TestContext<Unit>.selectKotlinLanguage() {
        step("Select the kotlin language") {
            flakySafely(KOTLIN_LANGUAGE_SELECTION_TIMEOUT_MS) {
                val kotlinText = device.targetContext.getString(R.string.lang_kotlin)
                openProjectLanguageDropdown()

                val d = device.uiDevice
                val kotlin = d.findObject(UiSelector().text(kotlinText))
                check(kotlin.waitForExists(LANGUAGE_OPTION_TIMEOUT_MS)) { "Kotlin language option not found" }
                kotlin.click()
                d.waitForIdle()
            }
        }
    }

    private fun TestContext<Unit>.openProjectLanguageDropdown() {
        val d = device.uiDevice
        val javaText = device.targetContext.getString(R.string.lang_java)
        val kotlinText = device.targetContext.getString(R.string.lang_kotlin)
        val languageLabelText = device.targetContext.getString(R.string.wizard_language)

        val javaValue = d.findObject(UiSelector().text(javaText))
        if (javaValue.waitForExists(LANGUAGE_OPTION_TIMEOUT_MS)) {
            val bounds = javaValue.visibleBounds
            d.click(bounds.centerX(), bounds.centerY())
            d.waitForIdle()
            if (d.findObject(UiSelector().text(kotlinText)).waitForExists(LANGUAGE_DROPDOWN_EXPANSION_TIMEOUT_MS)) {
                return
            }
        }

        val languageLabel = d.findObject(UiSelector().text(languageLabelText))
        if (languageLabel.waitForExists(LANGUAGE_OPTION_TIMEOUT_MS)) {
            val bounds = languageLabel.visibleBounds
            d.click(d.displayWidth - LANGUAGE_DROPDOWN_FALLBACK_X_OFFSET, bounds.centerY())
            d.waitForIdle()
            if (d.findObject(UiSelector().text(kotlinText)).waitForExists(LANGUAGE_DROPDOWN_EXPANSION_TIMEOUT_MS)) {
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
            check(byText.waitForExists(PROJECT_NAME_FIELD_TIMEOUT_MS)) { "Project name field not found" }
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
