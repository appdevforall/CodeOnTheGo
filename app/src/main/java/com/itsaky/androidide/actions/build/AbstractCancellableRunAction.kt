package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.BaseBuildAction
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
abstract class AbstractCancellableRunAction(
	context: Context,
	@StringRes private val labelRes: Int,
	@DrawableRes private val iconRes: Int,
) : BaseBuildAction() {
	// Execute on UI thread as this action might try to show dialogs to the user
	final override var requiresUIThread: Boolean = true

	init {
		label = context.getString(labelRes)
		icon = ContextCompat.getDrawable(context, iconRes)
		enabled = false
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)
		val context =
			data.getActivity() ?: run {
				markInvisible()
				return
			}

		if (data
				.getActivity()
				.isBuildInProgress() &&
			id == QuickRunAction.ID
		) {
			label = context.getString(R.string.title_cancel_build)
			icon = ContextCompat.getDrawable(context, R.drawable.ic_stop_daemons)
		} else {
			label = context.getString(labelRes)
			icon = ContextCompat.getDrawable(context, iconRes)
		}

		visible = true
		enabled = true
	}

	/**
	 * Called before the action is executed.
	 *
	 * @param data The action data.
	 * @return Whether to continue executing the action.
	 */
	protected open suspend fun preExec(data: ActionData): Boolean = true

	final override suspend fun execAction(data: ActionData): Any {
		if (!preExec(data)) return false

		if (data.getActivity().isBuildInProgress()) {
			return cancelBuild()
		}

		// An INTERNAL build (Quick Build's setup build) can own the single Gradle slot without
		// driving the editor's build UI, so this button correctly still reads "Run" - but
		// starting a second build would throw BuildInProgressException deep in the service and
		// surface as a raw error string. Say what is actually happening instead. The RAW flag,
		// on purpose: the slot really is busy even though the user has no build running.
		if (buildService?.isBuildInProgress == true) {
			data.getActivity()?.flashInfo(R.string.msg_build_slot_busy)
			return false
		}

		return doExec(data)
	}

	protected abstract fun doExec(data: ActionData): Any

	protected fun cancelBuild(): Boolean {
		log.info("Sending build cancellation request...")
		val builder = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
		if (builder?.isToolingServerStarted() != true) {
			flashError(com.itsaky.androidide.projects.R.string.msg_tooling_server_unavailable)
			return false
		}

		builder.cancelCurrentBuild().whenComplete {
			result,
			error,
			->
			if (error != null) {
				log.error("Failed to send build cancellation request", error)
				return@whenComplete
			}

			if (!result.wasEnqueued) {
				log.warn(
					"Unable to enqueue cancellation request reason={} reason.message={}",
					result.failureReason,
					result.failureReason!!.message,
				)
				return@whenComplete
			}

			log.info("Build cancellation request was successfully enqueued...")
		}

		return true
	}

	companion object {
		@JvmStatic
		protected val log: Logger =
			LoggerFactory.getLogger(AbstractCancellableRunAction::class.java)

		/**
		 * Whether the USER has a build running - what the stop affordance, the progress bar
		 * and the disabled-during-build actions key off. Reads
		 * [BuildService.isUserVisibleBuildInProgress], not the raw flag, so Quick Build's own
		 * setup build (same Gradle path, nobody asked for it) does not make this button claim
		 * to cancel a build the user never started.
		 */
		fun EditorHandlerActivity?.isBuildInProgress(): Boolean {
			val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
			return this?.editorViewModel?.let { it.isInitializing || it.isBuildInProgress } == true ||
				buildService?.isUserVisibleBuildInProgress == true
		}
	}
}
