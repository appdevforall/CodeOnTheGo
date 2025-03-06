package com.itsaky.androidide.helper

import com.itsaky.androidide.scenarios.InitializationProjectAndCancelingBuildScenario
import com.itsaky.androidide.scenarios.NavigateToMainScreenScenario
import com.itsaky.androidide.screens.TemplateScreen.selectTemplate
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

fun TestContext<Unit>.navigateToMainScreen() {
    step("Initialize the app and navigate to the Main Screen") {
        scenario(NavigateToMainScreenScenario())
    }
}

fun TestContext<Unit>.selectProjectTemplate(stepTitle: String, templateResId: Int) {
    step(stepTitle) {
        selectTemplate(templateResId)
    }
}

fun TestContext<Unit>.initializeProjectAndCancelBuild() {
    step("Initialization the project and cancelling the build") {
        scenario(InitializationProjectAndCancelingBuildScenario())
    }
}