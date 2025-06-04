package com.itsaky.androidide.screens

import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.R
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
            flakySafely(30000) {  // Increased timeout
                try {
                    ProjectSettingsScreen {
                        spinner {
                            isVisible()
                            open()
                            
                            // Wait for spinner to fully open
                            Thread.sleep(1000)

                            // Retry mechanism for selecting Kotlin
                            var attempts = 0
                            var success = false
                            while (attempts < 3 && !success) {
                                try {
                                    childAt<KSpinnerItem>(1) {
                                        isVisible()
                                        hasText("Kotlin")
                                        click()
                                    }
                                    success = true
                                } catch (e: Exception) {
                                    attempts++
                                    println("Failed to select Kotlin on attempt $attempts: ${e.message}")
                                    if (attempts < 3) {
                                        // Close and reopen spinner
                                        device.uiDevice.pressBack()
                                        Thread.sleep(1000)
                                        open()
                                        Thread.sleep(1000)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Error in selectKotlinLanguage: ${e.message}")
                    // One more attempt with a different approach
                    ProjectSettingsScreen {
                        spinner {
                            isVisible()
                            open()
                            
                            // Wait for spinner to fully open
                            Thread.sleep(1000)
                            
                            // Try to select by text instead of position
                            device.uiDevice.findObject(UiSelector().text("Kotlin")).click()
                        }
                    }
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