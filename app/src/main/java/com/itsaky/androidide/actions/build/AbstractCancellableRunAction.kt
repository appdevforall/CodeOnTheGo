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
        val context = data.getActivity() ?: run {
            markInvisible()
            return
        }

        if (data.getActivity().isBuildInProgress()) {
            label = context.getString(R.string.title_cancel_build)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_stop_daemons)
        } else {
            label = context.getString(labelRes)
            icon = ContextCompat.getDrawable(context, iconRes)
        }

        visible = true
        enabled = true
    }

    final override suspend fun execAction(data: ActionData): Any {
        if (data.getActivity().isBuildInProgress()) {
            return cancelBuild()
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

        builder.cancelCurrentBuild().whenComplete { result,
                                                    error ->
            if (error != null) {
                log.error("Failed to send build cancellation request", error)
                return@whenComplete
            }

            if (!result.wasEnqueued) {
                log.warn(
                    "Unable to enqueue cancellation request reason={} reason.message={}",
                    result.failureReason,
                    result.failureReason!!.message
                )
                return@whenComplete
            }

            log.info("Build cancellation request was successfully enqueued...")
        }

        return true
    }

    companion object {
        @JvmStatic
        protected val log: Logger = LoggerFactory.getLogger(AbstractCancellableRunAction::class.java)

        fun EditorHandlerActivity?.isBuildInProgress(): Boolean {
            val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
            return this?.editorViewModel?.let { it.isInitializing || it.isBuildInProgress } == true || buildService?.isBuildInProgress == true
        }
    }
}