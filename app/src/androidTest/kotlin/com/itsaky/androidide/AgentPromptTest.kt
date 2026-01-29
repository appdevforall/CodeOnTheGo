package com.itsaky.androidide

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.helper.navigateToMainScreen
import com.itsaky.androidide.helper.selectProjectTemplate
import com.itsaky.androidide.models.JdkDistribution
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectJavaLanguage
import com.itsaky.androidide.utils.Environment
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentPromptTest : TestCase() {

    @Before
    fun enableExperiments() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        preseedSetupState(instrumentation)
        instrumentation.uiAutomation.executeShellCommand(
            "mkdir -p /sdcard/Download && touch /sdcard/Download/CodeOnTheGo.exp"
        )
    }

    @After
    fun cleanUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "rm -f /sdcard/Download/CodeOnTheGo.exp"
        )
    }

    @Test
    fun test_agentPrompt_fromBottomSheet() {
        run {
            ActivityScenario.launch(SplashActivity::class.java)
            ensureProjectIsOpen()

            step("Drag bottom sheet up and verify init failed message") {
                flakySafely(60000) {
                    onView(withId(R.id.bottom_sheet)).perform(swipeUp())
                }

                val baseMsg = InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getString(
                        com.itsaky.androidide.resources.R.string.msg_project_initialization_failed_with_name,
                        ""
                    )
                    .trim()

                flakySafely(60000) {
                    onView(withId(R.id.statusText))
                        .check(matches(withText(containsString(baseMsg))))
                }
            }

            step("Verify project page is visible") {
                flakySafely(180000) {
                    onView(withId(R.id.editor_appBarLayout)).check(matches(isDisplayed()))
                }
            }

            step("Open bottom sheet and select Agent tab") {
                flakySafely(60000) {
                    onView(withId(R.id.bottom_sheet)).perform(swipeUp())
                }

                val agentTitle = InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getString(com.itsaky.androidide.resources.R.string.title_agent)
                val tabsSelector = UiSelector()
                    .resourceId("${BuildConfig.APPLICATION_ID}:id/bottom_sheet")
                    .childSelector(UiSelector().resourceId("${BuildConfig.APPLICATION_ID}:id/tabs"))

                val tabs = UiScrollable(tabsSelector).apply { setAsHorizontalList() }
                tabs.scrollTextIntoView(agentTitle)
                device.uiDevice.findObject(UiSelector().text(agentTitle)).click()

                val disclaimerOk = device.uiDevice.findObject(UiSelector().text("OK"))
                if (disclaimerOk.exists()) {
                    disclaimerOk.click()
                }
            }

            step("Send a prompt and verify a response") {
                val prompt = "Say hello"
                onView(withId(com.itsaky.androidide.agent.R.id.prompt_input_edittext))
                    .perform(click(), replaceText(prompt), closeSoftKeyboard())
                onView(withId(com.itsaky.androidide.agent.R.id.prompt_input_edittext))
                    .perform(pressKey(KeyEvent.KEYCODE_ENTER))

                flakySafely(60000) {
                    onView(
                        allOf(
                            withId(com.itsaky.androidide.agent.R.id.message_content),
                            withText(prompt)
                        )
                    ).check(matches(isDisplayed()))
                }

                flakySafely(120000) {
                    val agentFound = device.uiDevice.findObject(UiSelector().text("Agent")).exists()
                    val systemFound =
                        device.uiDevice.findObject(UiSelector().text("System")).exists()
                    val settingsFound = device.uiDevice.findObject(
                        UiSelector().text("Open AI Settings")
                    ).exists()
                    check(agentFound || systemFound || settingsFound) {
                        "No agent/system response found after sending prompt."
                    }
                }
            }
        }
    }

    private fun TestContext<Unit>.ensureProjectIsOpen() {
        val pkg = BuildConfig.APPLICATION_ID
        val editorSelector = UiSelector().resourceId("$pkg:id/editor_appBarLayout")
        val actionsSelector = UiSelector().resourceId("$pkg:id/actions")
        val onboardingSelector = UiSelector().resourceId("$pkg:id/next")

        if (device.uiDevice.findObject(editorSelector).exists()) {
            return
        }

        if (device.uiDevice.findObject(actionsSelector).exists()) {
            createEmptyProject()
            return
        }

        if (device.uiDevice.findObject(onboardingSelector).exists()) {
            navigateToMainScreen()
        }

        if (device.uiDevice.findObject(editorSelector).exists()) {
            return
        }

        if (device.uiDevice.findObject(actionsSelector).exists()) {
            createEmptyProject()
            return
        }

        navigateToMainScreen()
        createEmptyProject()
    }

    private fun TestContext<Unit>.createEmptyProject() {
        clickCreateProjectHomeScreen()
        selectProjectTemplate("Select the empty project", R.string.template_empty)
        selectJavaLanguage()
        clickCreateProjectProjectSettings()
    }

    private fun preseedSetupState(instrumentation: android.app.Instrumentation) {
        val targetContext = instrumentation.targetContext
        val baseDir = targetContext.filesDir
        val prefixDir = File(baseDir, "usr")
        val binDir = File(prefixDir, "bin")
        val jvmDir = File(prefixDir, "lib/jvm/java-21-openjdk")
        val javaBin = File(jvmDir, "bin/java")
        val bashBin = File(binDir, "bash")
        val androidHome = File(baseDir, "home/android-sdk")

        binDir.mkdirs()
        javaBin.parentFile.mkdirs()
        androidHome.mkdirs()

        if (!bashBin.exists()) {
            bashBin.writeText(
                "#!/system/bin/sh\nexec /system/bin/sh \"$@\"\n"
            )
            bashBin.setExecutable(true, false)
        }

        if (!javaBin.exists()) {
            javaBin.writeText(
                "#!/system/bin/sh\n" +
                        "echo \"java.home = ${jvmDir.absolutePath}\"\n" +
                        "echo \"java.version = 21.0.0\"\n"
            )
            javaBin.setExecutable(true, false)
        }

        // Ensure Environment points to expected paths.
        Environment.init(targetContext)

        // Seed JDK distributions so onboarding considers tools installed.
        try {
            val provider = IJdkDistributionProvider.getInstance()
            val field = provider::class.java.getDeclaredField("_installedDistributions")
            field.isAccessible = true
            field.set(provider, listOf(JdkDistribution("21.0.0", jvmDir.absolutePath)))
        } catch (_: Exception) {
            IJdkDistributionProvider.getInstance().loadDistributions()
        }

        val pkg = BuildConfig.APPLICATION_ID
        val uiAutomation = instrumentation.uiAutomation
        uiAutomation.executeShellCommand("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        uiAutomation.executeShellCommand("cmd appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        uiAutomation.executeShellCommand("cmd appops set --user 0 $pkg MANAGE_EXTERNAL_STORAGE allow")
        uiAutomation.executeShellCommand("cmd appops set --user 0 $pkg LEGACY_STORAGE allow")
        uiAutomation.executeShellCommand("pm grant $pkg android.permission.MANAGE_EXTERNAL_STORAGE")
        uiAutomation.executeShellCommand("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
        uiAutomation.executeShellCommand("pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE")
        uiAutomation.executeShellCommand("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        uiAutomation.executeShellCommand("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow")
        uiAutomation.executeShellCommand("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        uiAutomation.executeShellCommand("cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        uiAutomation.executeShellCommand("pm grant $pkg android.permission.REQUEST_INSTALL_PACKAGES")
        uiAutomation.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }
}
