package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.models.ApkMetadata
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.models.BasicAndroidVariantMetadata
import com.itsaky.androidide.utils.ApkInstaller
import com.itsaky.androidide.utils.InstallationResultHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @author Akash Yadav
 */
abstract class AbstractRunAction(
    context: Context,
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int,
): AbstractModuleAssemblerAction(context, labelRes, iconRes) {

    /**
     * Create the task execution message for the build.
     */
    protected abstract fun onCreateTaskExecMessage(
        data: ActionData,
        module: AndroidModule,
        variant: BasicAndroidVariantMetadata,
        buildService: BuildService,
        activity: EditorHandlerActivity
    ): TaskExecutionMessage

    override suspend fun doBuild(
        data: ActionData,
        module: AndroidModule,
        variant: BasicAndroidVariantMetadata,
        buildService: BuildService,
        activity: EditorHandlerActivity
    ): TaskExecutionResult? {
        val message = onCreateTaskExecMessage(
            data,
            module,
            variant,
            buildService,
            activity,
        )

        val result = withContext(Dispatchers.IO) {
            buildService.executeTasks(message).get()
        }

        if (result?.isSuccessful != true) {
            log.error("Tasks failed to execute: '{}'", message.tasks)
        }

        return result
    }

    override suspend fun handleResult(
        data: ActionData,
        result: TaskExecutionResult?,
        module: AndroidModule,
        variant: BasicAndroidVariantMetadata
    ) {
        if (result == null || !result.isSuccessful) {
            log.debug("Cannot install APK. Task execution failed.")
            return
        }

        log.debug("Installing APK(s) for project: '{}' variant: '{}'", module.path, variant.name)

        val main = variant.mainArtifact
        val outputListingFile = main.assembleTaskOutputListingFile
        if (outputListingFile == null) {
            log.error("No output listing file provided with project model")
            return
        }

        log.trace("Parsing metadata")
        val apkFile = ApkMetadata.findApkFile(outputListingFile)
        if (apkFile == null) {
            log.error("No apk file specified in output listing file: {}", outputListingFile)
            return
        }

        if (!apkFile.exists()) {
            log.error("APK file specified in output listing file does not exist! {}", apkFile)
            return
        }

        install(data, apkFile)
    }

    protected open suspend fun install(data: ActionData, apk: File) {
        val activity =
            data.getActivity()
                ?: run {
                    log.error("Cannot install APK. Unable to get activity instance.")
                    return
                }

        withContext(Dispatchers.Main) {
            log.debug("Installing APK: {}", apk)

            if (!apk.exists()) {
                log.error("APK file does not exist!")
                return@withContext
            }

            ApkInstaller.installApk(
                activity,
                InstallationResultHandler.createEditorActivitySender(activity),
                apk,
                activity.installationSessionCallback()
            )
        }
    }
}