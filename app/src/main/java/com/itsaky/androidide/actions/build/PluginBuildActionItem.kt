package com.itsaky.androidide.actions.build

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.BaseBuildAction
import com.itsaky.androidide.actions.getContext
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.manager.build.PluginBuildActionManager
import com.itsaky.androidide.plugins.manager.build.RegisteredBuildAction
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.manager.ui.PluginDrawableResolver
import com.itsaky.androidide.plugins.services.IdeCommandService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.resolveAttr
import com.itsaky.androidide.viewmodel.BottomSheetViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PluginBuildActionItem(
	context: Context,
	private val registered: RegisteredBuildAction,
	override val order: Int,
) : BaseBuildAction() {
	override val id: String = "plugin.build.${registered.pluginId}.${registered.action.id}"

	init {
		label = registered.action.name
		icon = resolvePluginIcon(context)
		location = ActionItem.Location.EDITOR_TOOLBAR
		requiresUIThread = true
	}

	override fun prepare(data: ActionData) {
		val context = data.getActivity()
		if (context == null) {
			visible = false
			return
		}
		visible = true

		val manager = PluginBuildActionManager.getInstance()
		val isRunning = manager.isActionRunning(registered.pluginId, registered.action.id)

		if (isRunning) {
			label = "Cancel ${registered.action.name}"
			icon = ContextCompat.getDrawable(context, R.drawable.ic_stop)
			enabled = true
		} else {
			label = registered.action.name
			icon = resolvePluginIcon(context)
			enabled = true
		}
	}

	override fun createColorFilter(data: ActionData): ColorFilter? {
		val context = data.getContext() ?: return null
		val isRunning =
			PluginBuildActionManager
				.getInstance()
				.isActionRunning(registered.pluginId, registered.action.id)
		val attr = if (isRunning) R.attr.colorError else R.attr.colorOnSurface
		return PorterDuffColorFilter(
			context.resolveAttr(attr),
			PorterDuff.Mode.SRC_ATOP,
		)
	}

	private fun resolvePluginIcon(fallbackContext: Context): Drawable? {
		val iconResId = registered.action.icon ?: return ContextCompat.getDrawable(fallbackContext, R.drawable.ic_run_outline)
		return PluginDrawableResolver.resolve(iconResId, registered.pluginId, fallbackContext)
			?: ContextCompat.getDrawable(fallbackContext, R.drawable.ic_run_outline)
	}

	override suspend fun execAction(data: ActionData): Any {
		val manager = PluginBuildActionManager.getInstance()
		val pluginId = registered.pluginId
		val actionId = registered.action.id

		if (manager.isActionRunning(pluginId, actionId)) {
			manager.cancelAction(pluginId, actionId)
			data.getActivity()?.let { resetProgressIfIdle(it) }
			return true
		}

		val activity = data.getActivity() ?: return false

		val pluginManager = PluginManager.getInstance() ?: return false
		val loadedPlugin = pluginManager.getLoadedPlugin(pluginId) ?: return false
		val commandService =
			loadedPlugin.context.services.get(IdeCommandService::class.java)
				?: return false

		val execution = manager.executeAction(pluginId, actionId, commandService) ?: return false

		activity.editorViewModel.isBuildInProgress = true
		val currentSheetState = activity.bottomSheetViewModel.sheetBehaviorState
		val targetState =
			if (currentSheetState == BottomSheetBehavior.STATE_HIDDEN) {
				BottomSheetBehavior.STATE_COLLAPSED
			} else {
				currentSheetState
			}
		activity.bottomSheetViewModel.setSheetState(
			sheetState = targetState,
			currentTab = BottomSheetViewModel.TAB_BUILD_OUTPUT,
		)
		// The tooling server streams a Gradle task's output into the pane itself, and clears the
		// pane when the build starts, so a header would be wiped.
		val echoOutput = registered.action.command !is CommandSpec.GradleTask
		if (echoOutput) activity.appendBuildOutput("━━━ ${registered.action.name} ━━━")
		activity.invalidateOptionsMenu()

		activity.lifecycleScope.launch(Dispatchers.Default) {
			runCatching {
				execution.output.collect { output ->
					if (!echoOutput) return@collect
					val line =
						when (output) {
							is CommandOutput.StdOut -> {
								output.line
							}

							is CommandOutput.StdErr -> {
								output.line
							}

							is CommandOutput.ExitCode -> {
								if (output.code != 0) "Process failed with code ${output.code}" else null
							}
						}
					if (line != null) {
						withContext(Dispatchers.Main) {
							activity.appendBuildOutput(line)
						}
					}
				}

				val result = execution.await()
				// A refused or timed-out task never reached the pane, so say why here.
				if (!echoOutput && result is CommandResult.Failure && result.exitCode == -1) {
					result.error?.let { withContext(Dispatchers.Main) { activity.appendBuildOutput(it) } }
				}
				manager.notifyActionCompleted(pluginId, actionId, result)
				withContext(Dispatchers.Main) { resetProgressIfIdle(activity) }
			}.onFailure { e ->
				if (e is CancellationException) {
					manager.cancelAction(pluginId, actionId)
					throw e
				}
				withContext(Dispatchers.Main) { resetProgressIfIdle(activity) }
			}
		}

		return true
	}

	private fun resetProgressIfIdle(activity: EditorHandlerActivity) {
		val manager = PluginBuildActionManager.getInstance()
		if (buildService?.isBuildInProgress != true && !manager.hasActiveExecutions()) {
			activity.editorViewModel.isBuildInProgress = false
		}
		activity.invalidateOptionsMenu()
	}
}
