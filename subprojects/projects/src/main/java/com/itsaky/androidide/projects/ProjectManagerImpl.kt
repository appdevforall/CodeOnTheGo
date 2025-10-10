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

package com.itsaky.androidide.projects

import androidx.annotation.RestrictTo
import com.google.auto.service.AutoService
import com.google.common.collect.ImmutableList
import com.itsaky.androidide.eventbus.events.EventReceiver
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.eventbus.events.project.ProjectInitializedEvent
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.JavaModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.models.resDirs
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.tooling.api.IAndroidProject
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.models.BuildVariantInfo
import com.itsaky.androidide.utils.DocumentUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.withStopWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString

/**
 * Internal implementation of [IProjectManager].
 *
 * @author Akash Yadav
 */
@AutoService(IProjectManager::class)
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class ProjectManagerImpl : IProjectManager, EventReceiver {

	lateinit var projectPath: String
	var projectInitialized: Boolean = false
	var cachedInitResult: InitializeResult? = null

	override var gradleBuild: GradleModels.GradleBuild? = null
	override var workspace: Workspace? = null

	override var androidBuildVariants: Map<String, BuildVariantInfo> = emptyMap()
		private set

	override val projectDirPath: String
		get() = projectPath

	override val projectSyncIssues: List<GradleModels.SyncIssue>?
		get() = gradleBuild?.syncIssueList ?: emptyList()

	companion object {

		private val log = LoggerFactory.getLogger(ProjectManagerImpl::class.java)

		@JvmStatic
		@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
		fun getInstance(): ProjectManagerImpl {
			return IProjectManager.getInstance() as ProjectManagerImpl
		}
	}

	override suspend fun setup(gradleBuild: GradleModels.GradleBuild) {
		this.gradleBuild = gradleBuild
		this.workspace = Workspace(
			rootProject = GradleProject(gradleBuild.rootProject),
			subProjects = gradleBuild.subProjectList.map { project ->
				when {
					project.hasAndroidProject() -> AndroidModule(project)
					project.hasJavaProject() -> JavaModule(project)
					else -> GradleProject(project)
				}
			},
			syncIssues = gradleBuild.syncIssueList
		)

		val workspace = this.workspace!!

		// build variants must be updated before the sources and class paths are indexed
		updateBuildVariants { buildVariants ->
			androidBuildVariants = buildVariants
		}

		log.info(
			"Found {} project sync issues: {}",
			gradleBuild.syncIssueCount,
			gradleBuild.syncIssueList
		)

		withStopWatch("Setup project") {
			val indexerScope = CoroutineScope(Dispatchers.Default)
			val modulesFlow = flow {
				workspace.subProjects.filterIsInstance<ModuleProject>().forEach {
					emit(it)
				}
			}

			val jobs = modulesFlow.map { module ->
				indexerScope.async {
					module.indexSourcesAndClasspaths()
					if (module is AndroidModule) {
						module.readResources()
					}
				}
			}

			// wait for the indexing to finish
			jobs.toList().awaitAll()
		}
	}

	override fun getAndroidModules(): List<AndroidModule> {
		val workspace = this.workspace ?: return emptyList()
		return workspace.subProjects.mapNotNull { module ->
			if (!module.hasAndroidProject()) {
				return@mapNotNull null
			}

			return@mapNotNull module as AndroidModule
		}
	}

	override fun getAndroidAppModules(): List<AndroidModule> {
		return getAndroidModules().filter { it.projectType == AndroidModels.ProjectType.ApplicationProject }
	}

	override fun getAndroidLibraryModules(): List<AndroidModule> {
		return getAndroidModules().filter { it.projectType == AndroidModels.ProjectType.LibraryProject }
	}

	override fun findModuleForFile(file: File, checkExistance: Boolean): ModuleProject? {
		if (!isInitialized()) {
			return null
		}

		return this.workspace!!.findModuleForFile(file, checkExistance)
	}

	override fun containsSourceFile(file: Path): Boolean {
		if (!isInitialized()) {
			return false
		}

		if (!Files.exists(file)) {
			return false
		}

		for (module in this.workspace!!.subProjects) {
			if (module !is ModuleProject) {
				continue
			}

			val source = module.compileJavaSourceClasses.findSource(file)
			if (source != null) {
				return true
			}
		}

		return false
	}

	override fun isAndroidResource(file: File): Boolean {
		val module = findModuleForFile(file) ?: return false
		if (module is AndroidModule) {
			return module.getResourceDirectories().find { file.path.startsWith(it.path) } != null
		}
		return true
	}

	override fun destroy() {
		log.info("Destroying project manager")
		this.workspace = null
		this.cachedInitResult = null
		this.projectInitialized = false

		(this.androidBuildVariants as? MutableMap?)?.clear()
	}

	@JvmOverloads
	fun generateSources(
		builder: BuildService? = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
	) {
		if (builder == null) {
			log.warn("Cannot generate sources. BuildService is null.")
			return
		}

		if (!builder.isToolingServerStarted()) {
			flashError(R.string.msg_tooling_server_unavailable)
			return
		}

		if (builder.isBuildInProgress) {
			return
		}

		val tasks = getAndroidModules().flatMap { module ->
			val variant = module.getSelectedVariant()
			if (variant == null) {
				log.error(
					"Selected build variant for project '{}' not found", module.path
				)
				return@flatMap emptyList()
			}

			val mainArtifact = variant.mainArtifact
			val variantNameCapitalized = variant.name.replaceFirstChar {
				if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
			}

			return@flatMap listOf(
				mainArtifact.resGenTaskName,
				mainArtifact.sourceGenTaskName,
				if (module.viewBindingOptions.isEnabled) "dataBindingGenBaseClasses$variantNameCapitalized" else null,
				"process${variantNameCapitalized}Resources"
			).mapNotNull { it?.let { "${module.path}:${it}" } }
		}


		builder.executeTasks(*tasks.toTypedArray()).whenComplete { result, taskErr ->
			if (result == null || !result.isSuccessful || taskErr != null) {
				log.warn(
					"Execution for tasks failed: {} {}", tasks, taskErr ?: ""
				)
			} else {
				notifyProjectUpdate()
			}
		}
	}

	fun notifyProjectUpdate() {

		executeAsync {
			workspace?.apply {
				subProjects.forEach { subproject ->
					if (subproject is ModuleProject) {
						subproject.indexSources()
					}
				}
			}

			val event = ProjectInitializedEvent()
			event.put(Workspace::class.java, workspace)
			EventBus.getDefault().post(event)
		}
	}

	private fun updateBuildVariants(onUpdated: (Map<String, BuildVariantInfo>) -> Unit = {}) {
		val workspace = checkNotNull(this.workspace) {
			"Cannot update build variants. Root project model is null."
		}

		val buildVariants = mutableMapOf<String, BuildVariantInfo>()
		workspace.subProjects.forEach { subproject ->
			if (subproject is AndroidModule) {

				// variant names are not expected to be modified
				val variantNames = ImmutableList.builder<String>()
					.addAll(subproject.variantList.map { variant -> variant.name }).build()

				val variantName =
					subproject.configuredVariantName
						?: subproject.variantList.firstOrNull()?.name
						?: IAndroidProject.DEFAULT_VARIANT

				buildVariants[subproject.path] =
					BuildVariantInfo(subproject.path, variantNames, variantName)
			}
		}

		onUpdated(buildVariants)
	}

	private fun isInitialized() = workspace != null

	private fun generateSourcesIfNecessary(event: FileEvent) {
		val builder = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return
		val file = event.file
		if (!isAndroidResource(file)) {
			return
		}

		generateSources(builder)
	}

	@Suppress("unused")
	@Subscribe(threadMode = ThreadMode.ASYNC)
	fun onFileSaved(event: DocumentSaveEvent) {
		event.file.apply {
			if (isDirectory()) {
				return@apply
			}

			if (extension != "xml") {
				return@apply
			}

			val module =
				IProjectManager.getInstance().findModuleForFile(this, false) ?: return@apply
			if (module !is AndroidModule) {
				return@apply
			}

			val isResource = module.mainSourceSet?.sourceProvider?.resDirs?.any {
				this.pathString.contains(it.path)
			} ?: false

			if (isResource) {
				module.updateResourceTable()
			}
		}
	}

	@Suppress("unused")
	@Subscribe(threadMode = ThreadMode.BACKGROUND)
	fun onFileCreated(event: FileCreationEvent) {
		generateSourcesIfNecessary(event)

		if (DocumentUtils.isJavaFile(event.file.toPath())) {
			IProjectManager.getInstance().findModuleForFile(event.file, false)?.let {
				val sourceRoot = it.findSourceRoot(event.file) ?: return@let

				// add the source node entry
				it.compileJavaSourceClasses.append(event.file.toPath(), sourceRoot)
			}
		}
	}

	@Suppress("unused")
	@Subscribe(threadMode = ThreadMode.BACKGROUND)
	fun onFileDeleted(event: FileDeletionEvent) {
		generateSourcesIfNecessary(event)

		// Remove the source node entry
		// Do not check for Java file DocumentUtils.isJavaFile(...) as it checks for file existence as
		// well. As the file is already deleted, it will always return false
		if (event.file.extension == "java") {
			IProjectManager.getInstance().findModuleForFile(
				event.file,
				false
			)?.compileJavaSourceClasses?.findSource(event.file.toPath())
				?.let { it.parent?.removeChild(it) }
		}
	}

	@Suppress("unused")
	@Subscribe(threadMode = ThreadMode.BACKGROUND)
	fun onFileRenamed(event: FileRenameEvent) {
		generateSourcesIfNecessary(event)

		// Do not check for Java file DocumentUtils.isJavaFile(...) as it checks for file existence as
		// well. As the file is already renamed to another filename, it will always return false
		if (event.file.extension == "java") {
			// remove the source node entry
			IProjectManager.getInstance().findModuleForFile(
				event.file,
				false
			)?.compileJavaSourceClasses?.findSource(event.file.toPath())
				?.let { it.parent?.removeChild(it) }
		}

		if (DocumentUtils.isJavaFile(event.newFile.toPath())) {
			IProjectManager.getInstance().findModuleForFile(event.newFile, false)?.let {
				val sourceRoot = it.findSourceRoot(event.newFile) ?: return@let
				// add the new source node entry
				it.compileJavaSourceClasses.append(event.newFile.toPath(), sourceRoot)
			}
		}
	}
}