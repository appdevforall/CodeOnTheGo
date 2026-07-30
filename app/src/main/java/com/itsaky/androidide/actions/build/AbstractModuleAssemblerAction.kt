package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.openApplicationModuleChooser
import com.itsaky.androidide.actions.profiler.ProfilerAction
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.viewmodel.BuildViewModel

/**
 * @author Akash Yadav
 */
abstract class AbstractModuleAssemblerAction(
	context: Context,
	@StringRes private val labelRes: Int,
	@DrawableRes private val iconRes: Int,
) : AbstractCancellableRunAction(context, labelRes, iconRes) {
	/**
	 * Extra Gradle arguments (e.g. `-P` properties) to pass for this action's build. Subclasses
	 * override this to influence the build; for example, the profiler action enables a profileable APK.
	 */
	protected open val gradleArgs: List<String>
		get() = emptyList()

	/**
	 * Resolves the variant that should actually be built for this action, given the user's
	 * [selectedVariant]. The default returns [selectedVariant] unchanged. Subclasses may override
	 * to build a different variant (e.g. the profiler builds the release counterpart). Returning
	 * `null` aborts the build; an overriding implementation must surface its own error first.
	 */
	protected open fun resolveBuildVariant(
		data: ActionData,
		module: AndroidModule,
		selectedVariant: AndroidModels.AndroidVariant,
	): AndroidModels.AndroidVariant? = selectedVariant

	override fun doExec(data: ActionData): Boolean {
		val projectManager = IProjectManager.getInstance()

		if (projectManager.isPluginProject()) {
			val module = projectManager.getAndroidModules().firstOrNull()
			if (module != null) {
				val variant = module.getSelectedVariant()
				if (variant != null) {
					onModuleSelected(data, module, variant)
					return true
				}
			}
			data.requireActivity().flashError(R.string.err_selected_variant_not_found)
			return false
		}

		openApplicationModuleChooser(data) { module ->
			val activity = data.requireActivity()

			val variant =
				module.getSelectedVariant() ?: run {
					activity.flashError(
						activity.getString(R.string.err_selected_variant_not_found),
					)
					return@openApplicationModuleChooser
				}

			onModuleSelected(data, module, variant)
		}
		return true
	}

	private fun onModuleSelected(
		data: ActionData,
		module: AndroidModule,
		variant: AndroidModels.AndroidVariant,
	) {
		val activity = data.requireActivity()
		val resolvedVariant = resolveBuildVariant(data, module, variant) ?: return
		// Resolved on the UI thread, which doExec already runs on: ViewModelProvider.get is
		// @MainThread and ViewModelLazy's cache is an unsynchronised field, so touching the
		// delegate from a background coroutine mutates the activity's ViewModelStore off-main.
		val buildViewModel: BuildViewModel by activity.viewModels()
		// Save, THEN build -- the build must be of what the user sees. The save runs INSIDE
		// runQuickBuild's coroutine, after it has reserved BuildState.InProgress, rather than
		// in actionScope here: a save on emulated storage is slow enough that a second tap
		// would otherwise slip past the already-in-progress guard, and actionScope dies with
		// the activity's onPause, which would have started a Gradle build from a cancelled
		// coroutine (the swallowed CancellationException made that invisible). A save failure
		// now aborts the build and is surfaced, instead of quietly building stale content.
		buildViewModel.runQuickBuild(
			module,
			resolvedVariant,
			launchInDebugMode = id == DebugAction.ID,
			launchProfilerAfterInstall = id == ProfilerAction.ID,
			gradleArgs = gradleArgs,
			beforeBuild = {
				// The activity can go away during the save; saving through a dead one is
				// pointless and its editors are already released.
				if (!activity.isDestroyed && !activity.isFinishing) {
					activity.saveAllResult()
				}
			},
		)
	}
}
