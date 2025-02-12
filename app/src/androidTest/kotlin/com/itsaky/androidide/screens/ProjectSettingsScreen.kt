package com.itsaky.androidide.screens

import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
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
            flakySafely {
                ProjectSettingsScreen {
                    spinner {
                        isVisible()
                        open()

                        childAt<KSpinnerItem>(1) {
                            isVisible()
                            hasText("Kotlin")
                            click()
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
}