package com.itsaky.androidide.utils

import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.tasks.executeAsyncProvideError
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.StringParameter
import com.itsaky.androidide.templates.Template
import com.itsaky.androidide.templates.impl.ConstraintVerifier
import org.eclipse.jgit.api.Git
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ProjectCreationManager(
	private val context: Context,
) {
	companion object {
		private val log = LoggerFactory.getLogger(ProjectCreationManager::class.java)
	}

	fun execute(
		template: Template<*>,
		onStart: () -> Unit,
		onSuccess: (ProjectTemplateRecipeResult, RecentProject) -> Unit,
		onError: (String) -> Unit,
	) {
		val isValid =
			template.parameters.filterIsInstance<StringParameter>().all { param ->
				ConstraintVerifier.isValid(param.value, param.constraints)
			}

		if (!isValid) {
			onError(context.getString(R.string.msg_invalid_project_details))
			return
		}

		onStart()

		executeAsyncProvideError({
			val result = template.recipe.execute(TemplateRecipeExecutor(context.applicationContext))
			if (result is ProjectTemplateRecipeResult && result.data.initGit) {
				try {
					Git
						.init()
						.setDirectory(result.data.projectDir)
						.call()
						.use { }
				} catch (e: Exception) {
					log.error("Failed to initialize git repository", e)
				}
			}
			result
		}) { result, err ->
			if (result == null || err != null || result !is ProjectTemplateRecipeResult) {
				err?.printStackTrace()
				val errorMsg = err?.cause?.message ?: err?.message ?: context.getString(R.string.project_creation_failed)
				onError(errorMsg)
				return@executeAsyncProvideError
			}

			val now = System.currentTimeMillis().toString()
			val project =
				RecentProject(
					location = result.data.projectDir.path,
					name = result.data.name,
					createdAt = now,
					lastModified = now,
					templateName = template.templateNameStr,
					language = result.data.language?.name ?: "unknown",
				)

			onSuccess(result, project)
		}
	}
}
