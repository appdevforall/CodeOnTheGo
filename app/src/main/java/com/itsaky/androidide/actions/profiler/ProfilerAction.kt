
package com.itsaky.androidide.actions.profiler

import android.content.Context
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.build.AbstractRunAction
import com.itsaky.androidide.actions.canShowPairingNotification
import com.itsaky.androidide.actions.showNotificationPermissionDialog
import com.itsaky.androidide.actions.showPairingDialog
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_PROFILEABLE_ENABLED
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.isAtLeastR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class ProfilerAction(
	context: Context,
	override val order: Int,
) : AbstractRunAction(
		context = context,
		labelRes = R.string.profiler_action_label,
		iconRes = R.drawable.ic_profiler,
	) {
	override val id: String = ID

	// Build a profileable APK: the Gradle plugin reads this property and applies
	// ProfilerPlugin to inject <profileable android:shell="true"/> into the merged manifest.
	override val gradleArgs: List<String> = listOf("-P$PROPERTY_PROFILEABLE_ENABLED=true")

	override fun resolveBuildVariant(
		data: ActionData,
		module: AndroidModule,
		selectedVariant: AndroidModels.AndroidVariant,
	): AndroidModels.AndroidVariant? {
		val allVariants =
			IProjectManager.getInstance().androidBuildVariants[module.path]?.buildVariants
				?: emptyList()

		val releaseName = releaseVariantName(selectedVariant.name, allVariants)
		val releaseVariant = releaseName?.let { module.getVariant(it) }

		if (releaseVariant == null) {
			val activity = data.requireActivity()
			activity.flashError(activity.getString(R.string.err_no_release_variant, module.path))
			return null
		}

		return releaseVariant
	}

	companion object {
		const val ID = "ide.editor.build.profiler"
	}

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = "" // todo: get a documentation tooltip!

	override fun prepare(data: ActionData) {
		super.prepare(data)

		if (IProjectManager.getInstance().isPluginProject()) {
			visible = false
			return
		}

		val buildIsInProgress = data.getActivity().isBuildInProgress()

		enabled = !(Shizuku.pingBinder()) || (!buildIsInProgress)
	}

	override suspend fun preExec(data: ActionData): Boolean {
		val activity = data.requireActivity()

		// The profiler relies on R-only platform APIs; @RequiresApi is lint-only, so guard at runtime
		// since the action is registered whenever experiments are enabled (mirrors DebugAction).
		if (!isAtLeastR()) {
			activity.flashError(R.string.err_profiler_requires_a11)
			return false
		}

		if (!canShowPairingNotification(activity)) {
			withContext(Dispatchers.Main.immediate) {
				showNotificationPermissionDialog(activity, onError = {
					log.error("Failed to open notification settings", it)
				})
			}
			return false
		}

		if (!Shizuku.pingBinder()) {
			log.error("Shizuku service is not running")
			withContext(Dispatchers.Main.immediate) {
				showPairingDialog(activity, log = log, onError = {
					log.error("Failed to open developer options", it)
				})
			}
			return false
		}

		return Shizuku.pingBinder()
	}
}
