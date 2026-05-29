package com.itsaky.androidide.helper

import com.itsaky.androidide.scenarios.InitializationProjectAndCancelingBuildScenario
import com.itsaky.androidide.scenarios.NavigateToMainScreenScenario
import com.itsaky.androidide.scenarios.RunAssembleTasksScenario
import com.itsaky.androidide.screens.TemplateScreen.selectTemplate
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

fun TestContext<Unit>.navigateToMainScreen() {
    step("Initialize the app and navigate to the Main Screen") {
        scenario(NavigateToMainScreenScenario())
    }
}

fun TestContext<Unit>.selectProjectTemplate(
    stepTitle: String,
    templateResId: Int,
    visibleTextOverride: String? = null,
) {
    step(stepTitle) {
        selectTemplate(templateResId, visibleTextOverride)
    }
}

fun TestContext<Unit>.initializeProjectAndCancelBuild() {
    step("Initialization the project and cancelling the build") {
        scenario(InitializationProjectAndCancelingBuildScenario())
    }
}

fun TestContext<Unit>.initializeProjectRunAssembleTasksAndCancelBuild() {
    step("Initialize project, quick-run debug build, and run assemble task set") {
        var failure: Throwable? = null
        try {
            scenario(InitializationProjectAndCancelingBuildScenario(closeProjectAfterBuild = false))
            scenario(RunAssembleTasksScenario())
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            runCatching {
                scenario(InitializationProjectAndCancelingBuildScenario.CloseProjectScenario())
            }.onFailure { closeFailure ->
                failure?.addSuppressed(closeFailure) ?: throw closeFailure
            }
        }
    }
}
