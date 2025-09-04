package com.itsaky.androidide.actions.build

import android.content.Context
import android.content.Intent
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.fragments.debug.DebuggerFragment
import com.itsaky.androidide.lsp.java.debug.JdwpOptions
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_DIR
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_INJECT
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_OPTIONS
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.models.BasicAndroidVariantMetadata
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.isAtLeastR
import com.itsaky.androidide.viewmodel.BottomSheetViewModel
import rikka.shizuku.Shizuku

/**
 * Run the application to
 *
 * @author Akash Yadav
 */
class DebugAction(
	context: Context,
	override val order: Int,
) : AbstractRunAction(
		context = context,
		labelRes = R.string.action_start_debugger,
		iconRes = R.drawable.ic_db_startdebugger,
	) {
	override val id = ID
    override fun retrieveTooltipTag(isReadOnlyContext: Boolean) = TooltipTag.EDITOR_TOOLBAR_DEBUG

	companion object {
		const val ID = "ide.editor.build.debug"
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)
		val buildIsInProgress = data.getActivity().isBuildInProgress()

		// should be enabled if Shizuku is not running
		// the user should not be required to wait for the build to complete
		// in order to start the ADB pairing process
		enabled = !(Shizuku.pingBinder()) || (JdwpOptions.JDWP_ENABLED && !buildIsInProgress)
	}

	override suspend fun preExec(data: ActionData): Boolean {
		val activity = data.requireActivity()
		if (!isAtLeastR()) {
			activity.flashError(R.string.err_debugger_requires_a11)
			return false
		}

		if (!Shizuku.pingBinder()) {
			log.error("Shizuku service is not running")
			// the debugger UI might be hidden if no VM is connected
			// in such cases, ensure that the debugger fragment is visible
			// before switching to the WADB pairing screen
			activity.bottomSheetViewModel.setSheetState(
				sheetState = BottomSheetBehavior.STATE_EXPANDED,
				currentTab = BottomSheetViewModel.TAB_DEBUGGER,
			)
			activity.debuggerViewModel.currentView = DebuggerFragment.VIEW_WADB_PAIRING
			return false
		}

		return Shizuku.pingBinder()
	}

	override fun onCreateTaskExecMessage(
		data: ActionData,
		module: AndroidModule,
		variant: BasicAndroidVariantMetadata,
		buildService: BuildService,
		activity: EditorHandlerActivity,
	): TaskExecutionMessage {
		val taskName = "${module.path}:${variant.mainArtifact.assembleTaskName}"
		log.info(
			"Running task '{}' to debug variant '{}' of project '{}'",
			taskName,
			variant.name,
			module.path,
		)

		val debugArgs = mutableListOf<String>()
		debugArgs.add("-P$PROP_JDWP_INJECT=false")
// 		debugArgs.add("-P$PROP_JDWP_INJECT=${JdwpOptions.JDWP_ENABLED}")
		if (JdwpOptions.JDWP_ENABLED) {
			debugArgs.add("-P$PROP_JDWP_DIR=${Environment.JDWP_DIR.absolutePath}")
			debugArgs.add("-P$PROP_JDWP_OPTIONS=${JdwpOptions.JDWP_OPTIONS}")
		}

		val executionMessage =
			TaskExecutionMessage(
				tasks = listOf(taskName),
				gradleArgs = debugArgs,
			)

		return executionMessage
	}

	override fun onCreateLaunchIntent(): Intent =
		super.onCreateLaunchIntent().apply {
			// add an extra value to indicate that the debugger should be started before launching
			// this application
			putExtra(ID, true)
		}
}
