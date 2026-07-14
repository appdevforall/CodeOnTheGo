package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.openApplicationModuleChooser
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.livereload.LiveReloadManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.IntentUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.viewmodel.BuildViewModel
import java.io.File
import kotlinx.coroutines.launch

/**
 * @author Akash Yadav
 */
abstract class AbstractModuleAssemblerAction(
	context: Context,
	@StringRes private val labelRes: Int,
	@DrawableRes private val iconRes: Int,
) : AbstractCancellableRunAction(context, labelRes, iconRes) {
	override fun doExec(data: ActionData): Boolean {
		val projectManager = IProjectManager.getInstance()

		// ADFA-4128 on-device live-reload. For a live-reload project (a `.livereload`
		// marker in the project root) with the on-device compile daemon staged:
		//   - FIRST run, or a manifest / Gradle / version-catalog change  -> full Gradle
		//     build + install (the manifest & dependency boundary the OS reads before code
		//     runs; falls through to the normal path below), then start the daemon.
		//   - otherwise -> fast loop: flush editors + on-device incremental build + launch
		//     the shell. No Gradle, no module chooser, no PackageInstaller, no Play Protect.
		val projectDir = projectManager.projectDir
		if (LiveReloadManager.isLiveReloadProject(data.requireActivity(), projectDir)) {
			val activity = data.requireActivity()
			val srcRoots = listOf(
				File(projectDir, "app/src/main/java"),
				File(projectDir, "app/src/main/kotlin"),
				File(projectDir, "app/src/main/res"),
			)
			if (LiveReloadManager.needsFullBuild(projectDir)) {
				// Snapshot the manifest/Gradle inputs + bring the daemon up, then let the
				// normal full Gradle build + install path below run.
				LiveReloadManager.recordFullBuild(projectDir)
				LiveReloadManager.ensureStarted(activity, projectDir, srcRoots)
			} else {
				LiveReloadManager.ensureStarted(activity, projectDir, srcRoots)
				actionScope.launch {
					activity.saveAllResult()                     // flush editors -> disk
					LiveReloadManager.requestBuild("code")        // on-device rebuild + deploy (debounced)
					IntentUtils.launchApp(activity, LiveReloadManager.SHELL_PACKAGE) // load the shell
				}
				return true
			}
		}

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
		val buildViewModel: BuildViewModel by activity.viewModels()
		actionScope.launch {
			activity.saveAllResult()
		}
		buildViewModel.runQuickBuild(module, variant, launchInDebugMode = id == DebugAction.ID)
	}
}
