package com.itsaky.androidide.screens

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import io.github.kakaocup.kakao.check.KCheckBox
import io.github.kakaocup.kakao.text.KButton
import io.github.kakaocup.kakao.text.KTextView
import com.google.android.material.textfield.TextInputLayout

object ProjectSettingsScreen : KScreen<ProjectSettingsScreen>() {

    override val layoutId: Int? = null

    override val viewClass: Class<*>? = null

    val createProjectButton = KButton {
        withText(R.string.create_project)
        withId(R.id.finish)
    }

    val languageSpinnerField = KTextView {
        withHint("Project Language")
    }

    val kotlinScriptText = KCheckBox {
        withText(R.string.msg_use_kts)
    }

    fun TestContext<Unit>.selectJavaLanguage() {
        step("Select the java language") {
            ProjectSettingsScreen {
                languageSpinnerField {
                    isVisible()
                    click()
                }
                
                device.uiDevice.waitForIdle(1000)
                
                // Use UiAutomator to find and click Java in the popup
                device.uiDevice.findObject(UiSelector().text("Java")).click()
                device.uiDevice.waitForIdle(500)
            }
        }
    }

    fun TestContext<Unit>.selectKotlinLanguage() {
        step("Select the kotlin language") {
            flakySafely(15000) {
                ProjectSettingsScreen {
                    languageSpinnerField {
                        isVisible()
                        click()
                    }
                    
                    device.uiDevice.waitForIdle(1000)
                    
                    // Use UiAutomator to find and click Kotlin in the popup
                    device.uiDevice.findObject(UiSelector().text("Kotlin")).click()
                    device.uiDevice.waitForIdle(500)
                }
            }
        }
    }

    fun TestContext<Unit>.clickCreateProjectProjectSettings() {
        step("Click create project on the Settings Page") {
            createProjectButton {
                isVisible()
                click()
            }
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