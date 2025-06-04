package com.itsaky.androidide.actions.build

import android.content.Context
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.lsp.java.debug.JdwpOptions
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_INJECT
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_LIBDIR
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_OPTIONS
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.models.BasicAndroidVariantMetadata
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Run the application to
 *
 * @author Akash Yadav
 */
class DebugAction(
    context: Context,
    override val order: Int
): AbstractRunAction(
    context = context,
    labelRes = R.string.action_start_debugger,
    iconRes = R.drawable.ic_db_startdebugger
) {

    override val id = "ide.editor.build.debug"

    override fun prepare(data: ActionData) {
        super.prepare(data)
        if (data.getActivity().isBuildInProgress()) {
            // if a build is in progress, then the 'Quick run' action will be used to
            // show the cancellation button
            visible = false
        }
        enabled = JdwpOptions.JDWP_ENABLED
    }

    override fun onCreateTaskExecMessage(
        data: ActionData,
        module: AndroidModule,
        variant: BasicAndroidVariantMetadata,
        buildService: BuildService,
        activity: EditorHandlerActivity
    ): TaskExecutionMessage {
        val taskName = "${module.path}:${variant.mainArtifact.assembleTaskName}"
        log.info("Running task '{}' to assemble variant '{}' of project '{}'", taskName, variant.name, module.path)

        val debugArgs = mutableListOf<String>()
        debugArgs.add("-P$PROP_JDWP_INJECT=${JdwpOptions.JDWP_ENABLED}")
        if (JdwpOptions.JDWP_ENABLED) {
            debugArgs.add("-P$PROP_JDWP_LIBDIR=${Environment.JDWP_LIB_DIR.absolutePath}")
            debugArgs.add("-P$PROP_JDWP_OPTIONS=${JdwpOptions.JDWP_OPTIONS}")
        }

        val executionMessage = TaskExecutionMessage(
            tasks = listOf(taskName),
            gradleArgs = debugArgs
        )

        return executionMessage
    }
}