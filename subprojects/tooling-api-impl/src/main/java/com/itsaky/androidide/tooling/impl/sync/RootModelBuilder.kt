/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.tooling.impl.sync

import com.android.builder.model.v2.ide.SyncIssue
import com.itsaky.androidide.builder.model.shouldBeIgnored
import com.itsaky.androidide.project.GradleBuild
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.ProjectModelInfo
import com.itsaky.androidide.project.SyncIssue
import com.itsaky.androidide.projects.models.projectDir
import com.itsaky.androidide.tooling.api.IAndroidProject
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.sync.ProjectSyncHelper
import com.itsaky.androidide.tooling.impl.Main
import com.itsaky.androidide.tooling.impl.util.configureFrom
import com.itsaky.androidide.utils.sha256
import kotlinx.coroutines.runBlocking
import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.model.idea.IdeaProject
import org.slf4j.LoggerFactory
import java.io.File
import java.io.Serializable

/**
 * Utility class to build the project models.
 *
 * @author Akash Yadav
 */
object RootModelBuilder :
	AbstractModelBuilder<RootProjectModelBuilderParams, File>(), Serializable {
	private val serialVersionUID = 1L

	private const val PROJECT_SYNC_LOCK_TIMEOUT_MS = 10 * 1000L

	override fun build(
		initializeParams: InitializeProjectParams,
		param: RootProjectModelBuilderParams,
	): File {
		val (projectConnection, cancellationToken, projectCacheFile, syncMetaFile) = param

		// do not reference the 'initializationParams' field in the
		val executor =
			projectConnection.action { controller ->
				val ideaProject = controller.getModelAndLog(IdeaProject::class.java)

				val ideaModules = ideaProject.modules
				val modulePaths =
					mapOf(*ideaModules.map { it.name to it.gradleProject.path }.toTypedArray())
				val rootModule =
					ideaModules.find { it.gradleProject.parent == null }
						?: throw ModelBuilderException(
							"Unable to find root project",
						)

				val rootProjectVersions = getAndroidVersions(rootModule, controller)

				val syncIssues = hashSetOf<SyncIssue>()
				val syncIssueReporter =
					ISyncIssueReporter { syncIssue ->
						if (syncIssue.shouldBeIgnored()) {
							// this SyncIssue should not be shown to the user
							return@ISyncIssueReporter
						}
						syncIssues.add(syncIssue)
					}

				val rootGradleProject =
					GradleProjectModelBuilder
						.build(initializeParams, rootModule.gradleProject)
						.toBuilder()

				if (rootProjectVersions != null) {
					// Root project is an Android project
					checkAgpVersion(rootProjectVersions, syncIssueReporter)
					val androidProject =
						AndroidProjectModelBuilder
							.build(
								initializeParams,
								AndroidProjectModelBuilderParams(
									controller,
									rootModule,
									rootProjectVersions,
									syncIssueReporter,
								),
							)

					rootGradleProject.setAndroidProject(androidProject)
				}

				val projects =
					ideaModules.map { ideaModule ->
						val gradleProject =
							GradleProjectModelBuilder
								.build(initializeParams, ideaModule.gradleProject)
								.toBuilder()

						val versions = getAndroidVersions(ideaModule, controller)
						if (versions != null) {
							checkAgpVersion(versions, syncIssueReporter)
							val androidProject =
								AndroidProjectModelBuilder
									.build(
										initializeParams,
										AndroidProjectModelBuilderParams(
											controller,
											ideaModule,
											versions,
											syncIssueReporter,
										),
									)

							gradleProject.setAndroidProject(androidProject)
						} else {
							val javaProject =
								JavaProjectModelBuilder
									.build(
										initializeParams,
										JavaProjectModelBuilderParams(ideaProject, ideaModule, modulePaths),
									)

							gradleProject.setJavaProject(javaProject)
						}

						gradleProject.build()
					}

				val gradleBuild =
					GradleBuild(
						rootProject = rootGradleProject.build(),
						subProjectList = projects,
						syncIssueList =
							syncIssues.map { syncIssue ->
								SyncIssue(
									data = syncIssue.data,
									message = syncIssue.message,
									multilineMessageList =
										syncIssue.multiLineMessage?.filterNotNull()
											?: emptyList(),
									type = syncIssue.type,
									severity =
										when (syncIssue.severity) {
											SyncIssue.SEVERITY_ERROR -> GradleModels.SyncIssueSeverity.SeverityError
											SyncIssue.SEVERITY_WARNING -> GradleModels.SyncIssueSeverity.SeverityWarning
											else -> throw IllegalArgumentException("Unknown severity: ${syncIssue.severity}")
										},
								)
							},
					)

				// IF the IDE were running fully in a JVM environment, we would have
				// used protobuf-java instead of protobuf-javalite. Messages generated
				// by protobuf-java are java.io.Serializable, can cross the BuildExecutor
				// boundary, and we could have returned the GradleBuild model here.
				// But since we're using protobuf-javalite, the models are not serializable
				// and hence cannot cross the BuildExecutor boundary. As a result,
				// we write the model cache file here in the build executor itself.

				val projectDir = gradleBuild.rootProject.projectDir

				val success =
					ProjectSyncHelper.tryUseSyncLock(projectDir, PROJECT_SYNC_LOCK_TIMEOUT_MS) {
						ProjectSyncHelper.writeGradleBuildSync(
							gradleBuild = gradleBuild,
							targetFile = projectCacheFile,
						)

						runBlocking {
							syncMetaFile.outputStream().buffered().use { out ->
								ProjectSyncHelper
									.createSyncMeta(
										projectDir = projectDir,
										includeChecksum = true,
										projectModelInfo =
											ProjectModelInfo(
												projectCacheFile.absolutePath,
												projectCacheFile.sha256(),
											),
									).writeTo(out)
								out.flush()
							}
						}
					}

				if (!success) {
					throw ModelBuilderException("Failed to acquire sync lock. Unable to write cache.")
				}

				return@action projectCacheFile
			}

		executor.configureFrom(initializeParams)
		applyAndroidModelBuilderProps(executor)

		if (cancellationToken != null) {
			executor.withCancellationToken(cancellationToken)
		}

		val logger = LoggerFactory.getLogger("RootModelBuilder")
		logger.warn("Starting build. See build output for more details...")

		if (Main.client != null) {
			Main.client.logOutput("Starting build...")
		}

		val cacheFile = executor.run()
		logger.debug("Build action executed")

		return cacheFile
	}

	private fun applyAndroidModelBuilderProps(launcher: ConfigurableLauncher<*>) {
		launcher.addProperty(IAndroidProject.PROPERTY_BUILD_MODEL_ONLY, true)
		launcher.addProperty(IAndroidProject.PROPERTY_INVOKED_FROM_IDE, true)
	}

	private fun ConfigurableLauncher<*>.addProperty(
		property: String,
		value: Any,
	) {
		addArguments(String.format("-P%s=%s", property, value))
	}
}
