package com.itsaky.androidide.helper

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.screens.HomeScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import org.hamcrest.Matchers

private const val TAG = "EnsureHomeScreen"

private enum class PostOnboardingScreen {
    HOME,
    EDITOR,
    UNKNOWN,
}

/** Gradle often hides [println]; logcat + stderr show these reliably. */
private fun testLog(msg: String) {
    Log.e(TAG, msg)
    System.err.println("$TAG: $msg")
}

/**
 * After onboarding, [MainActivity] may open the last project. We log state (logcat: `EnsureHomeScreen`),
 * then if we appear to be in the editor: open the drawer (hamburger), tap close project, tap
 * **Close without saving**. If still not home, disable auto-open, clear last path, and relaunch
 * [MainActivity] — **preferences are not restored** so auto-open cannot immediately reopen the
 * project during the same instrumentation run.
 */
fun TestContext<Unit>.ensureOnHomeScreenBeforeCreateProject() {
    testLog("ensureOnHomeScreenBeforeCreateProject() entered")

    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val pkg = BuildConfig.APPLICATION_ID
    val getStartedId = "$pkg:id/getStarted"
    val getStartedText = targetContext.getString(R.string.get_started)

    step("Decline auto-open last project if confirmation dialog is shown") {
        runCatching {
            val title = targetContext.getString(R.string.title_confirm_open_project)
            val dialogTitle = device.uiDevice.findObject(UiSelector().text(title))
            if (dialogTitle.waitForExists(4000) && dialogTitle.exists()) {
                testLog("Dismissing 'open last project?' dialog")
                val noLabel = targetContext.getString(R.string.no)
                runCatching { device.uiDevice.findObject(UiSelector().text(noLabel)).click() }
                device.uiDevice.waitForIdle(2000)
            }
        }
    }

    step("Ensure main home — detect screen, drawer close, or relaunch") {
        flakySafely(timeoutMs = 180_000) {
            device.uiDevice.waitForIdle(4000)

            val state = detectPostOnboardingScreen(device.uiDevice, pkg, getStartedId)
            testLog("Post-onboarding screen=$state (package=$pkg)")

            when (state) {
                PostOnboardingScreen.EDITOR ->
                    runCatching {
                        testLog("Attempting drawer → close project → close without saving")
                        closeProjectViaDrawerThenCloseWithoutSaving(device.uiDevice, targetContext)
                    }.onFailure { testLog("Drawer close failed: ${it.message}") }

                PostOnboardingScreen.UNKNOWN ->
                    testLog("Screen UNKNOWN — will try relaunch if home not visible")

                PostOnboardingScreen.HOME -> testLog("Screen looks like HOME already")
            }

            device.uiDevice.waitForIdle(3000)

            if (isGetStartedVisible(device.uiDevice, getStartedId)) {
                testLog("Get started visible — asserting HomeScreen")
                HomeScreen {
                    title {
                        isVisible()
                        withText(Matchers.equalToIgnoringCase(getStartedText))
                    }
                }
                return@flakySafely
            }

            testLog("Home still missing — relaunch MainActivity (autoOpen=false, clear last path)")
            GeneralPreferences.autoOpenProjects = false
            GeneralPreferences.lastOpenedProject = GeneralPreferences.NO_OPENED_PROJECT
            relaunchMainActivityClearTask(targetContext)
            device.uiDevice.waitForIdle(6000)

            testLog("After relaunch, asserting HomeScreen")
            HomeScreen {
                title {
                    isVisible()
                    withText(Matchers.equalToIgnoringCase(getStartedText))
                }
            }
        }
    }
}

private fun detectPostOnboardingScreen(d: UiDevice, pkg: String, getStartedId: String): PostOnboardingScreen {
    val editorById = d.findObject(UiSelector().resourceId("$pkg:id/editor_appBarLayout"))
    val editorByPattern = d.findObject(UiSelector().resourceIdMatches(".*:id/editor_appBarLayout"))
    for (node in listOf(editorById, editorByPattern)) {
        if (node.waitForExists(3000) && node.exists()) {
            val b = runCatching { node.visibleBounds }.getOrNull()
            if (b != null && b.width() > 4 && b.height() > 4) {
                return PostOnboardingScreen.EDITOR
            }
        }
    }

    val home = d.findObject(UiSelector().resourceId(getStartedId))
    if (home.waitForExists(3000) && home.exists()) {
        val hb = runCatching { home.visibleBounds }.getOrNull()
        if (hb != null && hb.width() > 4 && hb.height() > 4) {
            return PostOnboardingScreen.HOME
        }
    }

    return PostOnboardingScreen.UNKNOWN
}

private fun isGetStartedVisible(d: UiDevice, getStartedId: String): Boolean {
    val byId = d.findObject(UiSelector().resourceId(getStartedId))
    val nodes = listOf(byId, d.findObject(UiSelector().resourceIdMatches(".*:id/getStarted")))
    for (n in nodes) {
        if (n.waitForExists(2000) && n.exists()) {
            val b = runCatching { n.visibleBounds }.getOrNull() ?: continue
            if (b.width() > 4 && b.height() > 4) {
                return true
            }
        }
    }
    val text = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.get_started)
    val byText = d.findObject(UiSelector().text(text))
    return byText.waitForExists(2000) && byText.exists() &&
        runCatching { byText.visibleBounds }.getOrNull()?.let { it.width() > 4 && it.height() > 4 } == true
}

private fun closeProjectViaDrawerThenCloseWithoutSaving(d: UiDevice, context: Context) {
    val openDrawer = context.getString(R.string.cd_drawer_open)
    val closeDrawer = context.getString(R.string.cd_drawer_close)
    val closeProjectLabel = context.getString(R.string.title_close_project)
    val closeWithoutSaving = context.getString(R.string.close_without_saving)

    testLog("Looking for drawer nav: desc='$openDrawer' or '$closeDrawer'")
    val navToClick =
        listOf(
            UiSelector().description(openDrawer),
            UiSelector().description(closeDrawer),
            UiSelector().descriptionContains(openDrawer),
            UiSelector().descriptionContains(closeDrawer),
        ).firstNotNullOfOrNull { sel ->
            val o = d.findObject(sel)
            if (o.waitForExists(5000) && o.exists()) o else null
        }

    if (navToClick == null) {
        testLog("Drawer icon not found by description — trying first toolbar ImageButton")
        val btn = d.findObject(UiSelector().className("android.widget.ImageButton").instance(0))
        if (btn.waitForExists(3000) && btn.exists()) {
            runCatching { btn.click() }
        } else {
            error("No navigation / ImageButton for drawer")
        }
    } else {
        runCatching { navToClick.click() }
    }
    d.waitForIdle(2500)

    testLog("Looking for close-project control (desc or text='$closeProjectLabel')")
    val closeControl =
        listOf(
            UiSelector().description(closeProjectLabel),
            UiSelector().descriptionContains(closeProjectLabel),
            UiSelector().text(closeProjectLabel),
            UiSelector().textContains(closeProjectLabel),
        ).firstNotNullOfOrNull { sel ->
            val o = d.findObject(sel)
            if (o.waitForExists(10000) && o.exists()) o else null
        } ?: error("Close project control not found")

    runCatching { closeControl.click() }
    d.waitForIdle(3000)

    testLog("Looking for '$closeWithoutSaving'")
    val withoutSaving =
        listOf(
            UiSelector().text(closeWithoutSaving),
            UiSelector().textContains("without saving"),
        ).firstNotNullOfOrNull { sel ->
            val o = d.findObject(sel)
            if (o.waitForExists(20000) && o.exists()) o else null
        } ?: error("Close without saving not found")

    runCatching { withoutSaving.click() }
    d.waitForIdle(4000)
}

private fun relaunchMainActivityClearTask(context: Context) {
    val intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    context.startActivity(intent)
}

/** Logcat: `adb logcat -s OnboardNavigate:E` (plus stderr in Gradle when shown). */
const val TAG_ONBOARD_NAV = "OnboardNavigate"

fun logOnboardingNavigation(msg: String) {
    Log.e(TAG_ONBOARD_NAV, msg)
    System.err.println("$TAG_ONBOARD_NAV: $msg")
}

/**
 * After "Finish installation" on the permissions slide, [com.itsaky.androidide.fragments.onboarding.PermissionsFragment]
 * runs IDE setup — there is **no** guarantee that AppIntro's `@id/done` exists ([OnboardingActivity] does not add
 * [com.itsaky.androidide.fragments.onboarding.IdeSetupConfigurationFragment] as a slide). Poll until **Get started**
 * or **editor** is visible.
 */
fun waitForMainHomeOrEditorUi(device: UiDevice, maxWaitMs: Long = 300_000L) {
    val deadline = System.currentTimeMillis() + maxWaitMs
    var lastLog = 0L
    logOnboardingNavigation("Waiting up to ${maxWaitMs / 1000}s for main home or editor after IDE setup…")
    while (System.currentTimeMillis() < deadline) {
        if (mainHomeOrEditorVisible(device)) {
            logOnboardingNavigation("Main UI visible (home Get started or editor app bar)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastLog > 30_000L) {
            val left = (deadline - now) / 1000
            logOnboardingNavigation("Still waiting… ~${left}s left (IDE setup can take minutes)")
            lastLog = now
        }
        device.waitForIdle(2000)
        Thread.sleep(400)
    }
    error("Timeout ${maxWaitMs}ms — main home / editor never appeared after IDE setup")
}

private fun mainHomeOrEditorVisible(device: UiDevice): Boolean {
    val getStarted = device.findObject(UiSelector().resourceIdMatches(".*:id/getStarted"))
    if (getStarted.waitForExists(600) && getStarted.exists()) {
        val b = runCatching { getStarted.visibleBounds }.getOrNull()
        if (b != null && b.width() > 4 && b.height() > 4) {
            return true
        }
    }
    val editor = device.findObject(UiSelector().resourceIdMatches(".*:id/editor_appBarLayout"))
    if (editor.waitForExists(600) && editor.exists()) {
        val b = runCatching { editor.visibleBounds }.getOrNull()
        if (b != null && b.width() > 4 && b.height() > 4) {
            return true
        }
    }
    return false
}
