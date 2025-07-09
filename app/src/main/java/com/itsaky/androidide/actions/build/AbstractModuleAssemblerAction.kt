package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.openApplicationModuleChooser
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.models.BasicAndroidVariantMetadata
import com.itsaky.androidide.utils.flashError
import kotlinx.coroutines.Dispatchers
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
        openApplicationModuleChooser(data) { module ->
            val activity = data.requireActivity()

            val variant = module.getSelectedVariant() ?: run {
                activity.flashError(
                    activity.getString(R.string.err_selected_variant_not_found))
                return@openApplicationModuleChooser
            }

            onModuleSelected(data, module, variant)
        }
        return true
    }

    private fun onModuleSelected(
        data: ActionData,
        module: AndroidModule,
        variant: BasicAndroidVariantMetadata
    ) {
        val buildService = this.buildService ?: run {
            log.error("Cannot execute task. BuildService not found.")
            return
        }

        if (!buildService.isToolingServerStarted()) {
            flashError(com.itsaky.androidide.R.string.msg_tooling_server_unavailable)
            return
        }

        val activity =
            data.getActivity()
                ?: run {
                    log.error(
                        "Cannot execute task. Activity instance not provided in ActionData.")
                    return
                }

        actionScope.launch(Dispatchers.Default) {
            activity.saveAllResult()

            val result = doBuild(
                data,
                module,
                variant,
                buildService,
                activity
            )

            log.debug("Task execution result: {}", result)

            handleResult(data, result, module, variant)
        }.invokeOnCompletion { error ->
            if (error != null) {
                log.error("Failed to run task", error)
            }
        }
    }

    protected abstract suspend fun doBuild(
        data: ActionData,
        module: AndroidModule,
        variant: BasicAndroidVariantMetadata,
        buildService: BuildService,
        activity: EditorHandlerActivity,
    ): TaskExecutionResult?

    protected abstract suspend fun handleResult(
        data: ActionData,
        result: TaskExecutionResult?,
        module: AndroidModule,
        variant: BasicAndroidVariantMetadata
    )
}