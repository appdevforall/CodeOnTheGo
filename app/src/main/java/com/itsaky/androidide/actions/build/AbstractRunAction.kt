package com.itsaky.androidide.actions.build

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.models.BasicAndroidVariantMetadata

/**
 * @author Akash Yadav
 */
abstract class AbstractRunAction(
	context: Context,
	@StringRes labelRes: Int,
	@DrawableRes iconRes: Int,
) : AbstractModuleAssemblerAction(context, labelRes, iconRes) {
	/**
	 * Create the task execution message for the build.
	 */
	protected open fun onCreateTaskExecMessage(
		data: ActionData,
		module: AndroidModule,
		variant: BasicAndroidVariantMetadata,
		buildService: BuildService,
		activity: EditorHandlerActivity,
	): TaskExecutionMessage {
		val taskName = "${module.path}:${variant.mainArtifact.assembleTaskName}"
		log.info(
			"Running task '{}' to assemble variant '{}' of project '{}'",
			taskName,
			variant.name,
			module.path,
		)

		return TaskExecutionMessage(tasks = listOf(taskName))
	}

	protected open fun onCreateLaunchIntent() = Intent()
}
