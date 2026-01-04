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

package com.itsaky.androidide.activities.editor

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.MarginLayoutParams
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.GravityInt
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blankj.utilcode.util.SizeUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_FIND_ACTION_MENU
import com.itsaky.androidide.actions.ActionsRegistry.Companion.getInstance
import com.itsaky.androidide.actions.etc.FindInFileAction
import com.itsaky.androidide.actions.etc.FindInProjectAction
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.databinding.LayoutSearchProjectBinding
import com.itsaky.androidide.flashbar.Flashbar
import com.itsaky.androidide.fragments.FindActionDialog
import com.itsaky.androidide.fragments.sheets.ProgressSheet
import com.itsaky.androidide.handlers.EditorBuildEventListener
import com.itsaky.androidide.handlers.LspHandler.connectClient
import com.itsaky.androidide.handlers.LspHandler.connectDebugClient
import com.itsaky.androidide.handlers.LspHandler.destroyLanguageServers
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.IDELanguageClientImpl
import com.itsaky.androidide.lsp.java.utils.CancelChecker
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.models.projectDir
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.services.builder.GradleBuildServiceConnnection
import com.itsaky.androidide.services.builder.gradleDistributionParams
import com.itsaky.androidide.tooling.api.messages.AndroidInitializationParams
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.CACHE_READ_ERROR
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_DIRECTORY_INACCESSIBLE
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_DIRECTORY
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_FOUND
import com.itsaky.androidide.tooling.api.messages.result.failure
import com.itsaky.androidide.tooling.api.messages.result.isSuccessful
import com.itsaky.androidide.tooling.api.models.BuildVariantInfo
import com.itsaky.androidide.tooling.api.models.mapToSelectedVariants
import com.itsaky.androidide.tooling.api.sync.ProjectSyncHelper
import com.itsaky.androidide.utils.DURATION_INDEFINITE
import com.itsaky.androidide.utils.DialogUtils.newMaterialDialogBuilder
import com.itsaky.androidide.utils.RecursiveFileSearcher
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.flashbarBuilder
import com.itsaky.androidide.utils.onLongPress
import com.itsaky.androidide.utils.resolveAttr
import com.itsaky.androidide.utils.DialogUtils.showRestartPrompt
import com.itsaky.androidide.utils.showOnUiThread
import com.itsaky.androidide.utils.withIcon
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.viewmodel.BuildState
import com.itsaky.androidide.viewmodel.BuildVariantsViewModel
import com.itsaky.androidide.viewmodel.BuildViewModel
import org.koin.android.ext.android.inject
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import org.adfa.constants.CONTENT_KEY
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern
import java.util.stream.Collectors

/** @author Akash Yadav */
@Suppress("MemberVisibilityCanBePrivate")
abstract class ProjectHandlerActivity : BaseEditorActivity() {
	protected val buildVariantsViewModel by viewModels<BuildVariantsViewModel>()
	private val pluginRepository: PluginRepository by inject()

	protected var mSearchingProgress: ProgressSheet? = null
	protected var mFindInProjectDialog: AlertDialog? = null
	protected var syncNotificationFlashbar: Flashbar? = null

	private val buildViewModel by viewModels<BuildViewModel>()
	protected var initializingFuture: CompletableFuture<out InitializeResult?>? = null

	val findInProjectDialog: AlertDialog
		get() {
			if (mFindInProjectDialog == null) {
				createFindInProjectDialog()
			}
			return mFindInProjectDialog!!
		}

	fun findActionDialog(actionData: ActionData): FindActionDialog {
		val shouldHideFindInFileAction = editorViewModel.getOpenedFileCount() != 0
		val registry = getInstance() as DefaultActionsRegistry

		return FindActionDialog(
			anchor = content.customToolbar.findViewById(R.id.menu_container),
			context = this,
			actionData = actionData,
			shouldShowFindInFileAction = shouldHideFindInFileAction,
			onFindInFileClicked = { data ->
				val findInFileAction =
					registry.findAction(
						location = EDITOR_FIND_ACTION_MENU,
						id = FindInFileAction().id,
					)
				if (findInFileAction != null) {
					registry.executeAction(findInFileAction, data)
				}
			},
			onFindInProjectClicked = { data ->
				val findInProjectAction =
					registry.findAction(
						location = EDITOR_FIND_ACTION_MENU,
						id = FindInProjectAction().id,
					)
				if (findInProjectAction != null) {
					registry.executeAction(findInProjectAction, data)
				}
			},
		)
	}

	protected val mBuildEventListener = EditorBuildEventListener()

	private val buildServiceConnection = GradleBuildServiceConnnection()

	companion object {
		const val STATE_KEY_FROM_SAVED_INSTANACE = "ide.editor.isFromSavedInstance"
		const val STATE_KEY_SHOULD_INITIALIZE = "ide.editor.isInitializing"
	}

	abstract fun doCloseAll()

	abstract fun saveOpenedFiles()

	override fun doDismissSearchProgress() {
		if (mSearchingProgress?.isShowing == true) {
			mSearchingProgress!!.dismiss()
		}
	}

	override fun doOpenHelp() {
		openHelpActivity()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		editorViewModel._isSyncNeeded.observe(this) { isSyncNeeded ->
			if (!isSyncNeeded) {
				// dismiss if already showing
				syncNotificationFlashbar?.dismiss()
				return@observe
			}

			if (syncNotificationFlashbar?.isShowing() == true) {
				// already shown
				return@observe
			}

			notifySyncNeeded()
		}

		observeStates()
		startServices()
	}

	private fun observeStates() {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					buildViewModel.buildState.collect { onBuildStateChanged(it) }
				}
			}
		}
	}

	private fun onBuildStateChanged(state: BuildState) {
		editorViewModel.isBuildInProgress = (state is BuildState.InProgress)
		when (state) {
			is BuildState.Idle -> {
				// Nothing to do, build is finished or not started.
			}

			is BuildState.InProgress -> {
				setStatus(getString(R.string.status_building))
			}

			is BuildState.Success -> {
				flashSuccess(state.message)
			}

			is BuildState.Error -> {
				flashError(state.reason)
			}

			is BuildState.AwaitingInstall -> {
				installApk(state)
				buildViewModel.installationAttempted()
			}

			is BuildState.AwaitingPluginInstall -> {
				showPluginInstallDialog(state.cgpFile)
			}
		}
		// Refresh the toolbar icons (e.g., the run/stop button).
		invalidateOptionsMenu()
	}

	private fun installApk(state: BuildState.AwaitingInstall) {
		apkInstallationViewModel.installApk(
			context = this,
			apk = state.apkFile,
			launchInDebugMode = state.launchInDebugMode,
		)
	}

	private fun showPluginInstallDialog(cgpFile: File) {
		if (!cgpFile.exists()) {
			flashError(getString(string.msg_plugin_file_not_found))
			buildViewModel.pluginInstallationAttempted()
			return
		}
		val pluginName = cgpFile.nameWithoutExtension
		newMaterialDialogBuilder(this)
			.setTitle(string.title_install_plugin)
			.setMessage(getString(string.msg_install_plugin_prompt, pluginName))
			.setPositiveButton(string.btn_install) { dialog, _ ->
				dialog.dismiss()
				installPlugin(cgpFile)
			}
			.setNegativeButton(string.btn_later) { dialog, _ ->
				dialog.dismiss()
				buildViewModel.pluginInstallationAttempted()
			}
			.setOnCancelListener {
				buildViewModel.pluginInstallationAttempted()
			}
			.show()
	}

	private fun installPlugin(cgpFile: File) {
		lifecycleScope.launch {
			setStatus(getString(string.status_installing_plugin))
			val result = pluginRepository.installPluginFromFile(cgpFile)
			result.onSuccess {
				showRestartPrompt(this@ProjectHandlerActivity)
			}.onFailure { error ->
				flashError(getString(string.msg_plugin_install_failed, error.message ?: "Unknown error"))
			}
			setStatus("")
			buildViewModel.pluginInstallationAttempted()
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.apply {
			putBoolean(STATE_KEY_SHOULD_INITIALIZE, !editorViewModel.isInitializing)
			putBoolean(STATE_KEY_FROM_SAVED_INSTANACE, true)
		}
	}

	override fun onPause() {
		super.onPause()
		if (isDestroying) {
			// reset these values here
			// sometimes, when the IDE closed and reopened instantly, these values prevent initialization
			// of the project
			ProjectManagerImpl.getInstance().destroy()

			editorViewModel.isInitializing = false
			editorViewModel.isBuildInProgress = false
		}
	}

	override fun onResume() {
		super.onResume()

		val service = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService
		editorViewModel.isBuildInProgress = service?.isBuildInProgress == true
		editorViewModel.isInitializing = initializingFuture?.isDone == false

		invalidateOptionsMenu()
	}

	override fun preDestroy() {
		syncNotificationFlashbar?.dismiss()
		syncNotificationFlashbar = null

		if (isDestroying) {
			releaseServerListener()
			this.initializingFuture?.cancel(true)
			this.initializingFuture = null

			doCloseAll()
		}

		if (IDELanguageClientImpl.isInitialized()) {
			IDELanguageClientImpl.shutdown()
		}

		super.preDestroy()

		if (isDestroying) {
			try {
				stopLanguageServers()
			} catch (_: Exception) {
				log.error("Failed to stop editor services.")
			}

			try {
				unbindService(buildServiceConnection)
				buildServiceConnection.onConnected = {}
			} catch (_: Throwable) {
				log.error("Unable to unbind service")
			} finally {
				Lookup.getDefault().apply {
					(lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService?)
						?.setEventListener(null)

					unregister(BuildService.KEY_BUILD_SERVICE)
				}

				mBuildEventListener.release()
				editorViewModel.isBoundToBuildSerice = false
			}
		}
	}

	fun setStatus(status: CharSequence) {
		setStatus(status, Gravity.CENTER)
	}

	fun setStatus(
		status: CharSequence,
		@GravityInt gravity: Int,
	) {
		doSetStatus(status, gravity)
	}

	fun appendBuildOutput(str: String) {
		content.bottomSheet.appendBuildOut(str)
	}

	fun notifySyncNeeded() {
		notifySyncNeeded { initializeProject() }
	}

	private fun notifySyncNeeded(onConfirm: () -> Unit) {
		val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
		if (buildService == null || editorViewModel.isInitializing || buildService.isBuildInProgress) return

		activityScope.launch(Dispatchers.Main.immediate) {
			syncNotificationFlashbar?.dismiss()
			syncNotificationFlashbar =
				flashbarBuilder(
					duration = DURATION_INDEFINITE,
					backgroundColor = resolveAttr(R.attr.colorSecondaryContainer),
					messageColor = resolveAttr(R.attr.colorOnSecondaryContainer),
				).withIcon(
					R.drawable.ic_sync,
					colorFilter = resolveAttr(R.attr.colorOnSecondaryContainer),
				).message(string.msg_sync_needed)
					.positiveActionText(string.btn_sync)
					.positiveActionTapListener {
						onConfirm()
						it.dismiss()
					}.negativeActionText(string.btn_ignore_changes)
					.negativeActionTapListener(Flashbar::dismiss)
					.build()

			syncNotificationFlashbar?.showOnUiThread()
		}
	}

	fun startServices() {
		val service =
			Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as GradleBuildService?
		if (editorViewModel.isBoundToBuildSerice && service != null) {
			log.info("Reusing already started Gradle build service")
			onGradleBuildServiceConnected(service)
			return
		} else {
			log.info("Binding to Gradle build service...")
		}

		buildServiceConnection.onConnected = this::onGradleBuildServiceConnected

		if (
			bindService(
				Intent(this, GradleBuildService::class.java),
				buildServiceConnection,
				BIND_AUTO_CREATE or BIND_IMPORTANT,
			)
		) {
			log.info("Bind request for Gradle build service was successful...")
		} else {
			log.error("Gradle build service doesn't exist or the IDE is not allowed to access it.")
		}

		initLspClient()
	}

	fun initializeProject(forceSync: Boolean = false) {
		val currentVariants = buildVariantsViewModel._buildVariants.value

		// no information about the build variants is available
		// use the default variant selections
		if (currentVariants == null) {
			log.debug(
				"No variant selection information available. " +
					"Default build variants will be selected.",
			)
			initializeProject(buildVariants = emptyMap(), forceSync = forceSync)
			return
		}

		// variant selection information is available
		// but there are updated & unsaved variant selections
		// use the updated variant selections to initialize the project
		if (buildVariantsViewModel.updatedBuildVariants.isNotEmpty()) {
			val newSelections = currentVariants.toMutableMap()
			newSelections.putAll(buildVariantsViewModel.updatedBuildVariants)

			val selectedVariants = newSelections.mapToSelectedVariants()
			log.debug("Initializing project with new build variant selections: {}", selectedVariants)

			initializeProject(buildVariants = selectedVariants, forceSync = forceSync)
			return
		}

		// variant selection information is available but no variant selections have been updated
		// the user might be trying to sync the project from options menu
		// initialize the project with the existing selected variants
		val selectedVariants = currentVariants.mapToSelectedVariants()
		log.debug("Re-initializing project with existing build variant selections")
		initializeProject(buildVariants = selectedVariants, forceSync = forceSync)
	}

	private fun showToast(message: String) {
		Toast.makeText(this@ProjectHandlerActivity, message, Toast.LENGTH_LONG).show()
	}

	private suspend fun handleMissingProjectDirectory(projectName: String) = withContext(Dispatchers.Main) {
		recentProjectsViewModel.deleteProject(projectName)
		showToast(getString(string.msg_project_dir_doesnt_exist))

		val intent = Intent(this@ProjectHandlerActivity, MainActivity::class.java).apply {
			addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		}

		startActivity(intent)
		this@ProjectHandlerActivity.finish()
	}

	/**
	 * Initialize (sync) the project.
	 *
	 * @param buildVariants A map of project paths to the selected build variants.
	 */
	fun initializeProject(
		buildVariants: Map<String, String>,
		forceSync: Boolean = false,
	) = activityScope.launch {
		val manager = ProjectManagerImpl.getInstance()
		val projectDir = File(manager.projectPath)
		if (!projectDir.exists()) {
			log.error("GradleProject directory does not exist. Cannot initialize project")
			handleMissingProjectDirectory(projectDir.name)
			return@launch
		}

		val needsSync = try {
			forceSync || manager.isGradleSyncNeeded(projectDir)
		} catch (e: Exception) {
			when (e) {
				is FileNotFoundException -> {
					handleMissingProjectDirectory(projectDir.name)
					return@launch
				}
				else -> throw e
			}
		}

		withContext(Dispatchers.Main.immediate) {
			preProjectInit()
		}

		val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
		if (buildService == null) {
			log.error("No build service found. Cannot initialize project.")
			return@launch
		}

		if (!buildService.isToolingServerStarted()) {
			flashError(string.msg_tooling_server_unavailable)
			return@launch
		}

		log.info("Sending init request to tooling server (needs sync: {})...", needsSync)
		initializingFuture = buildService.initializeProject(params = createProjectInitParams(projectDir, buildVariants, needsSync))

		initializingFuture!!.whenCompleteAsync { result, error ->
			releaseServerListener()

			if (result == null || !result.isSuccessful || error != null) {
				if (!CancelChecker.isCancelled(error)) {
					log.error("An error occurred initializing the project with Tooling API", error)
				}

				activityScope.launch(context = Dispatchers.Main) {
					postProjectInit(isSuccessful = false, failure = result?.failure)
				}
				return@whenCompleteAsync
			}

			onProjectInitialized(result as InitializeResult.Success)
		}
	}

	private fun createProjectInitParams(
		projectDir: File,
		buildVariants: Map<String, String>,
		needsGradleSync: Boolean,
	): InitializeProjectParams =
		InitializeProjectParams(
			directory = projectDir.absolutePath,
			gradleDistribution = gradleDistributionParams,
			androidParams = createAndroidParams(buildVariants),
			needsGradleSync = needsGradleSync,
		)

	private fun createAndroidParams(buildVariants: Map<String, String>): AndroidInitializationParams {
		if (buildVariants.isEmpty()) {
			return AndroidInitializationParams.DEFAULT
		}

		return AndroidInitializationParams(buildVariants)
	}

	private fun releaseServerListener() {
		// Release reference to server listener in order to prevent memory leak
		(Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService?)
			?.setServerListener(null)
	}

	fun stopLanguageServers() {
		try {
			destroyLanguageServers(isChangingConfigurations)
		} catch (err: Throwable) {
			log.error("Unable to stop editor services. Please report this issue.", err)
		}
	}

	protected fun onGradleBuildServiceConnected(service: GradleBuildService) {
		log.info("Connected to Gradle build service")

		buildServiceConnection.onConnected = null
		editorViewModel.isBoundToBuildSerice = true
		Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, service)
		service.setEventListener(mBuildEventListener)

		if (service.isToolingServerStarted()) {
			initializeProject()
			return
		}

		service.startToolingServer { pid ->
			memoryUsageWatcher.watchProcess(pid, PROC_GRADLE_TOOLING)
			resetMemUsageChart()

			service.metadata().whenComplete { metadata, err ->
				if (metadata == null || err != null) {
					log.error("Failed to get tooling server metadata")
					return@whenComplete
				}

				if (pid != metadata.pid) {
					log.warn(
						"Tooling server pid mismatch. Expected: {}, Actual: {}. Replacing memory watcher...",
						pid,
						metadata.pid,
					)
					memoryUsageWatcher.watchProcess(metadata.pid, PROC_GRADLE_TOOLING)
					resetMemUsageChart()
				}
			}

			initializeProject()
		}
	}

	protected open fun onProjectInitialized(result: InitializeResult.Success) {
		editorActivityScope.launch(Dispatchers.IO) {
			val manager = ProjectManagerImpl.getInstance()
			val gradleBuildResult = ProjectSyncHelper.readGradleBuild(result.cacheFile)
			if (gradleBuildResult.isFailure) {
				val error = gradleBuildResult.exceptionOrNull()
				log.error("Failed to read project cache", error)
				if (error != null) {
					Sentry.captureException(error)
				}

				withContext(Dispatchers.Main) { postProjectInit(false, CACHE_READ_ERROR) }
				return@launch
			}

			manager.setup(gradleBuildResult.getOrThrow())
			manager.notifyProjectUpdate()
			updateBuildVariants(manager.androidBuildVariants)

			withContext(Dispatchers.Main) {
				postProjectInit(isSuccessful = true, failure = null)
			}
		}
	}

	protected open fun preProjectInit() {
		setStatus(getString(string.msg_initializing_project))
		editorViewModel.isInitializing = true
	}

	protected open fun postProjectInit(
		isSuccessful: Boolean,
		failure: TaskExecutionResult.Failure?,
	) {
		val manager = ProjectManagerImpl.getInstance()
		if (!isSuccessful) {
			// Get project name for error message
			val projectName =
				try {
					val project = manager.workspace?.rootProject
					if (project != null) {
						project.name.takeIf { it.isNotEmpty() }
							?: manager.projectDir.name
					} else {
						manager.projectDir.name
					}
				} catch (th: Throwable) {
					manager.projectDir.name
				}

			val initFailed =
				if (projectName.isNotEmpty()) {
					getString(string.msg_project_initialization_failed_with_name, projectName)
				} else {
					getString(string.msg_project_initialization_failed)
				}
			setStatus(initFailed)

			val msg =
				when (failure) {
					PROJECT_DIRECTORY_INACCESSIBLE -> string.msg_project_dir_inaccessible
					PROJECT_NOT_DIRECTORY -> string.msg_file_is_not_dir
					PROJECT_NOT_FOUND -> string.msg_project_dir_doesnt_exist
					CACHE_READ_ERROR -> string.msg_project_cache_read_failure
					else -> null
				}?.let {
					"$initFailed: ${getString(it)}"
				}

			flashError(msg ?: initFailed)

			editorViewModel.isInitializing = false
			return
		}

		initialSetup()
		setStatus(getString(string.msg_project_initialized))
		editorViewModel.isInitializing = false

		if (mFindInProjectDialog?.isShowing == true) {
			mFindInProjectDialog!!.dismiss()
		}

		mFindInProjectDialog = null // Create the dialog again if needed
	}

	private fun updateBuildVariants(buildVariants: Map<String, BuildVariantInfo>) {
		// avoid using the 'runOnUiThread' method defined in the activity
		com.itsaky.androidide.tasks.runOnUiThread {
			buildVariantsViewModel.buildVariants = buildVariants
			buildVariantsViewModel.resetUpdatedSelections()
		}
	}

	protected open fun createFindInProjectDialog(): AlertDialog? {
		val manager = ProjectManagerImpl.getInstance()
		if (manager.workspace == null) {
			log.warn("No root project model found. Is the project initialized?")
			flashError(getString(string.msg_project_not_initialized))
			return null
		}

		val moduleDirs =
			try {
				manager.gradleBuild!!
					.subProjectList
					.stream()
					.map { project -> project.projectDir }
					.collect(Collectors.toList())
			} catch (_: Throwable) {
				flashError(getString(string.msg_no_modules))
				emptyList()
			}

		return createFindInProjectDialog(moduleDirs)
	}

	protected open fun createFindInProjectDialog(moduleDirs: List<File>): AlertDialog? {
		val srcDirs = mutableListOf<File>()
		val binding = LayoutSearchProjectBinding.inflate(layoutInflater)
		binding.modulesContainer.removeAllViews()

		for (i in moduleDirs.indices) {
			val module = moduleDirs[i]
			val src = File(module, "src")

			if (!module.exists() || !module.isDirectory || !src.exists() || !src.isDirectory) {
				continue
			}

			val check = CheckBox(this)
			check.text = module.name
			check.isChecked = true

			val params = MarginLayoutParams(-2, -2)
			params.bottomMargin = SizeUtils.dp2px(4f)
			binding.modulesContainer.addView(check, params)
			srcDirs.add(src)
		}

		val builder = newMaterialDialogBuilder(this)
		builder.setTitle(string.menu_find_project)
		builder.setView(binding.root)
		builder.setCancelable(false)
		builder.setPositiveButton(string.menu_find) { dialog, _ ->
			val text =
				binding.input.editText!!
					.text
					.toString()
					.trim()
			if (text.isEmpty()) {
				flashError(string.msg_empty_search_query)
				return@setPositiveButton
			}

			val searchDirs = mutableListOf<File>()
			for (i in 0 until binding.modulesContainer.childCount) {
				val check = binding.modulesContainer.getChildAt(i) as CheckBox
				if (check.isChecked) {
					searchDirs.add(srcDirs[i])
				}
			}

			val extensions =
				binding.filter.editText!!
					.text
					.toString()
					.trim()
			val extensionList = mutableListOf<String>()
			if (extensions.isNotEmpty()) {
				if (extensions.contains("|")) {
					for (
					str in
					extensions
						.split(Pattern.quote("|").toRegex())
						.dropLastWhile { it.isEmpty() }
						.toTypedArray()
					) {
						if (str.trim().isEmpty()) {
							continue
						}
						extensionList.add(str)
					}
				} else {
					extensionList.add(extensions)
				}
			}

			if (searchDirs.isEmpty()) {
				flashError(string.msg_select_search_modules)
			} else {
				dialog.dismiss()

				getProgressSheet(string.msg_searching_project)?.apply {
					show(supportFragmentManager, "search_in_project_progress")
				}

				RecursiveFileSearcher.searchRecursiveAsync(
					text,
					extensionList,
					searchDirs,
				) { results ->
					handleSearchResults(results)
				}
			}
		}

		builder.setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
		val dialog = builder.create()
		dialog.onLongPress {
			TooltipManager.showIdeCategoryTooltip(
				context = this,
				anchorView = binding.root,
				tag = TooltipTag.DIALOG_FIND_IN_PROJECT,
			)
			true
		}

		mFindInProjectDialog = dialog
		return mFindInProjectDialog
	}

	private fun initialSetup() {
		val manager = ProjectManagerImpl.getInstance()
		try {
			val project = manager.workspace?.rootProject
			if (project == null) {
				log.warn("GradleProject not initialized. Skipping initial setup...")
				return
			}

			var projectName = project.name
			if (projectName.isEmpty()) {
				projectName = manager.projectDir.name
			}

			supportActionBar!!.subtitle = projectName
		} catch (_: Throwable) {
			// ignored
		}
	}

	private fun openHelpActivity() {
		val intent = Intent(this, HelpActivity::class.java)
		intent.putExtra(CONTENT_KEY, getString(string.docs_url))
		startActivity(intent)
	}

	private fun initLspClient() {
		if (!IDELanguageClientImpl.isInitialized()) {
			IDELanguageClientImpl.initialize(this as EditorHandlerActivity)
		}
		connectClient(IDELanguageClientImpl.getInstance())
		connectDebugClient(debuggerViewModel.debugClient)
	}

	open fun getProgressSheet(msg: Int): ProgressSheet? {
		doDismissSearchProgress()

		mSearchingProgress =
			ProgressSheet().also {
				it.isCancelable = false
				it.setMessage(getString(msg))
				it.setSubMessageEnabled(false)
			}

		return mSearchingProgress
	}
}
