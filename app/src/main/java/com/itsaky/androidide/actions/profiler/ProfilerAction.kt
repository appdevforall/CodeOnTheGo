
package com.itsaky.androidide.actions.profiler

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.build.AbstractRunAction
import com.itsaky.androidide.actions.canShowPairingNotification
import com.itsaky.androidide.actions.showNotificationPermissionDialog
import com.itsaky.androidide.actions.showPairingDialog
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_PROFILEABLE_ENABLED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku


class ProfilerAction(context: Context, override val order: Int) :
	AbstractRunAction(
		context = context,
		labelRes = R.string.quick_run_debug,
		iconRes = R.drawable.ic_profiler
	) {

	override val id: String = ID

	// Build a profileable APK: the Gradle plugin reads this property and applies
	// ProfilerPlugin to inject <profileable android:shell="true"/> into the merged manifest.
	override val gradleArgs: List<String> = listOf("-P$PROPERTY_PROFILEABLE_ENABLED=true")

	companion object {
		const val ID = "ide.editor.build.profiler"
	}

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String =
		"" // todo: get a documentation tooltip!

    override fun prepare(data: ActionData) {
        super.prepare(data)

        if (IProjectManager.getInstance().isPluginProject()) {
            visible = false
            return
        }

        val buildIsInProgress = data.getActivity().isBuildInProgress()

        enabled = !(Shizuku.pingBinder()) || (!buildIsInProgress)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun preExec(data: ActionData): Boolean {
        val activity = data.requireActivity()

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
