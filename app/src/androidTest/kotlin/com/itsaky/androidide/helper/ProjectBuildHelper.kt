package com.itsaky.androidide.helper

import android.util.Log
import com.itsaky.androidide.scenarios.EditorNewTemplateProjectScenario
import com.itsaky.androidide.scenarios.NavigateToMainScreenScenario
import com.itsaky.androidide.screens.TemplateScreen.selectTemplate
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

private const val TAG_NAV_MAIN = "NavigateToMain"

fun TestContext<Unit>.navigateToMainScreen() {
	Log.e(TAG_NAV_MAIN, "navigateToMainScreen() starting")
	System.err.println("$TAG_NAV_MAIN: starting")
	step("Initialize the app and navigate to the Main Screen") {
		scenario(NavigateToMainScreenScenario())
	}
	// Run after scenario completes — not inside [Scenario] steps (clearer lifecycle, reliable logs).
	ensureOnHomeScreenBeforeCreateProject()
}

fun TestContext<Unit>.selectProjectTemplate(stepTitle: String, templateResId: Int) {
    step(stepTitle) {
        selectTemplate(templateResId)
    }
}

fun TestContext<Unit>.initializeProjectAndCancelBuild() {
	step("Editor: first-build dialog, init, quick run, close project") {
		scenario(EditorNewTemplateProjectScenario())
	}
}