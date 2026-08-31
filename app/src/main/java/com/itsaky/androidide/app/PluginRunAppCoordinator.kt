package com.itsaky.androidide.app

import android.app.Activity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.activities.editor.ProjectHandlerActivity
import com.itsaky.androidide.interfaces.IEditorHandler
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.viewmodel.BuildState
import com.itsaky.androidide.viewmodel.BuildViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the current project's app on behalf of a plugin.
 *
 * Delegates to [BuildViewModel.runQuickBuild], the same entry point as the editor's Run action, so
 * a plugin gets APK resolution, the install and the launch prompt rather than a bare assemble.
 * That path is activity-scoped, which is why this needs the foreground activity and the main
 * thread; a plugin calls in from a background coroutine.
 */
internal object PluginRunAppCoordinator {
	private val logger = LoggerFactory.getLogger(PluginRunAppCoordinator::class.java)

	/**
	 * Builds, installs and launches the selected app module.
	 *
	 * @param foregroundActivity the activity currently on screen, or null when there is none.
	 * @param callback completed once, when the build resolves or the run is abandoned. Success
	 *   means the installer has the APK, not that the app is on screen: the system install prompt
	 *   and the launch prompt are the user's to answer.
	 */
	fun runApp(
		foregroundActivity: Activity?,
		callback: BuildAndLaunchCallback,
	) {
		val activity = foregroundActivity as? ProjectHandlerActivity
		if (activity == null) {
			callback.onComplete(false, "No project is open in the editor. Open one before running the app.")
			return
		}

		val reported = AtomicBoolean(false)

		fun report(
			success: Boolean,
			message: String,
		) {
			if (reported.compareAndSet(false, true)) {
				callback.onComplete(success, message)
			}
		}

		val job =
			activity.lifecycleScope.launch {
				try {
					startBuild(activity, ::report)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					logger.error("Failed to run the app for a plugin", e)
					report(false, "Error: ${e.message}")
				}
			}

		// An already-destroyed activity's scope drops the block without running it, so cancellation
		// has to be reported from the job rather than from inside. Nothing else will report, and the
		// caller would sit out its own timeout. A completed hand-off carries no cause, so the build
		// still reports through runQuickBuild's callback.
		job.invokeOnCompletion { cause ->
			if (cause != null) {
				report(false, "The editor was closed before the build started.")
			}
		}
	}

	/**
	 * Resolves what to build and starts it. Returns as soon as the build is under way: the outcome
	 * arrives on [BuildViewModel.runQuickBuild]'s callback, which outlives this coroutine.
	 */
	private suspend fun startBuild(
		activity: ProjectHandlerActivity,
		report: (Boolean, String) -> Unit,
	) {
		val projectManager = IProjectManager.getInstance()
		val isPluginProject = withContext(Dispatchers.IO) { projectManager.isPluginProject() }
		val module =
			if (isPluginProject) {
				projectManager.getAndroidModules().firstOrNull()
			} else {
				// The Run action asks the user which app module to build; a plugin has no one to
				// ask, so it gets the first, as the previous provider did.
				projectManager.getAndroidAppModules().firstOrNull()
			}
		val variant = module?.getSelectedVariant()
		if (module == null || variant == null) {
			report(false, "No app module or build variant is selected.")
			return
		}

		val buildViewModel = ViewModelProvider(activity)[BuildViewModel::class.java]
		(activity as? IEditorHandler)?.saveAllResult()
		buildViewModel.runQuickBuild(module, variant, launchInDebugMode = false) { state ->
			val (success, message) = state.toOutcome()
			report(success, message)
		}
	}

	/** What to tell the plugin about a run that ended on [this]. */
	private fun BuildState.toOutcome(): Pair<Boolean, String> =
		when (this) {
			is BuildState.AwaitingInstall -> {
				true to "Build succeeded. Installing ${apkFile.name} - confirm the system prompt to launch the app."
			}

			is BuildState.AwaitingPluginInstall -> {
				true to "Build succeeded. Installing plugin ${cgpFile.name} - confirm the prompt in the IDE."
			}

			is BuildState.Error -> {
				false to reason
			}

			is BuildState.Success -> {
				true to message
			}

			// runQuickBuild only ends on Idle when its scope was cancelled, and never on InProgress.
			BuildState.Idle -> {
				false to "The build was cancelled."
			}

			BuildState.InProgress -> {
				false to "The build did not report a result."
			}
		}
}
