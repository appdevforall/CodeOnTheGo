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

package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSelectedEvent
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.kotlin.compiler.Compiler
import com.itsaky.androidide.lsp.kotlin.diagnostic.KotlinDiagnosticProvider
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.bootClassPaths
import com.itsaky.androidide.utils.DocumentUtils
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds

class KotlinLanguageServer : ILanguageServer {

	private var _client: ILanguageClient? = null
	private var _settings: IServerSettings? = null
	private var selectedFile: Path? = null
	private var initialized = false

	private val scope =
		CoroutineScope(SupervisorJob() + CoroutineName(KotlinLanguageServer::class.simpleName!!))
	private var compiler: Compiler? = null
	private var diagnosticProvider: KotlinDiagnosticProvider? = null
	private var analyzeJob: Job? = null

	override val serverId: String = SERVER_ID

	override val client: ILanguageClient?
		get() = _client

	val settings: IServerSettings
		get() = _settings ?: KotlinServerSettings.getInstance().also { _settings = it }

	companion object {

		private val ANALYZE_DEBOUNCE_DELAY = 400.milliseconds

		const val SERVER_ID = "ide.lsp.kotlin"
		private val log = LoggerFactory.getLogger(KotlinLanguageServer::class.java)
	}

	init {
		applySettings(KotlinServerSettings.getInstance())

		if (!EventBus.getDefault().isRegistered(this)) {
			EventBus.getDefault().register(this)
		}
	}

	override fun shutdown() {
		EventBus.getDefault().unregister(this)
		scope.cancel("LSP is being shut down")
		compiler?.close()
		initialized = false
	}

	override fun connectClient(client: ILanguageClient?) {
		this._client = client
	}

	override fun applySettings(settings: IServerSettings?) {
		this._settings = settings
	}

	override fun setupWithProject(workspace: Workspace) {
		log.info("setupWithProject called, initialized={}", initialized)
		recreateSession(workspace)
		initialized = true
	}

	private fun recreateSession(workspace: Workspace) {
		diagnosticProvider?.close()
		compiler?.close()

		val jdkHome = Environment.JAVA_HOME.toPath()
		val jdkRelease = IJdkDistributionProvider.DEFAULT_JAVA_RELEASE
		val intellijPluginRoot = Paths.get(
			BaseApplication
				.baseInstance.applicationInfo.sourceDir
		)

		val jvmTarget = JvmTarget.fromString(IJdkDistributionProvider.DEFAULT_JAVA_VERSION)
			?: JvmTarget.JVM_21

		val jvmPlatform = JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)

		compiler = Compiler(
			intellijPluginRoot = intellijPluginRoot,
			jdkHome = jdkHome,
			jdkRelease = jdkRelease,
			languageVersion = LanguageVersion.LATEST_STABLE
		) {
			buildKtModuleProvider {
				platform = jvmPlatform

				val moduleProjects =
					workspace.subProjects
						.filterIsInstance<ModuleProject>()
						.filter { it.path != workspace.rootProject.path }

				val bootClassPaths =
					moduleProjects
						.filterIsInstance<AndroidModule>()
						.flatMap { project ->
							project.bootClassPaths
								.map { bootClassPath ->
									addModule(buildKtLibraryModule {
										this.platform = jvmPlatform
										this.libraryName = bootClassPath.nameWithoutExtension
										addBinaryRoot(bootClassPath.toPath())
									})
								}
						}

				val libraryDependencies =
					moduleProjects
						.flatMap { it.getCompileClasspaths() }
						.associateWith { library ->
							addModule(buildKtLibraryModule {
								this.platform = jvmPlatform
								this.libraryName = library.nameWithoutExtension
								addBinaryRoot(library.toPath())
							})
						}

				val subprojectsAsModules = mutableMapOf<ModuleProject, KaSourceModule>()

				fun getOrCreateModule(project: ModuleProject): KaSourceModule {
					subprojectsAsModules[project]?.also { module ->
						// a source module already exists for this project
						return module
					}

					val module = buildKtSourceModule {
						this.platform = jvmPlatform
						this.moduleName = project.name
						addSourceRoots(
							project.getSourceDirectories().map { it.toPath() })

						// always dependent on boot class paths, if any
						bootClassPaths.forEach { bootClassPathModule ->
							addRegularDependency(bootClassPathModule)
						}

						project.getCompileClasspaths(excludeSourceGeneratedClassPath = true)
							.forEach { classpath ->
								val libDependency = libraryDependencies[classpath]
								if (libDependency == null) {
									log.error(
										"Unable to locate library module for classpath: {}",
										libDependency
									)
									return@forEach
								}

								addRegularDependency(libDependency)
							}

						project.getCompileModuleProjects()
							.forEach { dependencyModule ->
								addRegularDependency(getOrCreateModule(dependencyModule))
							}
					}

					subprojectsAsModules[project] = module
					return module
				}

				moduleProjects.forEach { project ->
					addModule(getOrCreateModule(project))
				}
			}
		}

		diagnosticProvider = KotlinDiagnosticProvider(
			compiler = compiler!!,
			scope = scope,
		)
	}

	override fun complete(params: CompletionParams?): CompletionResult {
		return CompletionResult.EMPTY
	}

	override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
		if (!settings.referencesEnabled()) {
			return ReferenceResult.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return ReferenceResult.empty()
		}

		return ReferenceResult.empty()
	}

	override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
		if (!settings.definitionsEnabled()) {
			return DefinitionResult.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return DefinitionResult.empty()
		}

		return DefinitionResult.empty()
	}

	override suspend fun expandSelection(params: ExpandSelectionParams): Range {
		return params.selection
	}

	override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
		if (!settings.signatureHelpEnabled()) {
			return SignatureHelp.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return SignatureHelp.empty()
		}

		return SignatureHelp.empty()
	}

	override suspend fun analyze(file: Path): DiagnosticResult {
		log.debug("analyze(file={})", file)

		if (!settings.diagnosticsEnabled() || !settings.codeAnalysisEnabled()) {
			log.debug(
				"analyze() skipped: diagnosticsEnabled={}, codeAnalysisEnabled={}",
				settings.diagnosticsEnabled(), settings.codeAnalysisEnabled()
			)
			return DiagnosticResult.NO_UPDATE
		}

		if (!DocumentUtils.isKotlinFile(file)) {
			log.debug("analyze() skipped: not a Kotlin file")
			return DiagnosticResult.NO_UPDATE
		}

		return diagnosticProvider?.analyze(file)
			?: DiagnosticResult.NO_UPDATE
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentOpen(event: DocumentOpenEvent) {
		if (!DocumentUtils.isKotlinFile(event.openedFile)) {
			return
		}

		selectedFile = event.openedFile
		debouncingAnalyze()
	}

	private fun debouncingAnalyze() {
		analyzeJob?.cancel()
		analyzeJob = scope.launch(Dispatchers.Default) {
			delay(ANALYZE_DEBOUNCE_DELAY)
			analyzeSelected()
		}
	}

	private suspend fun analyzeSelected() {
		val file = selectedFile ?: return
		val client = _client ?: return

		if (!Files.exists(file)) return

		val result = analyze(file)
		withContext(Dispatchers.Main) {
			client.publishDiagnostics(result)
		}
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentChange(event: DocumentChangeEvent) {
		if (!DocumentUtils.isKotlinFile(event.changedFile)) {
			return
		}
		debouncingAnalyze()
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentClose(event: DocumentCloseEvent) {
		if (!DocumentUtils.isKotlinFile(event.closedFile)) {
			return
		}

		diagnosticProvider?.clearTimestamp(event.closedFile)
		if (FileManager.getActiveDocumentCount() == 0) {
			selectedFile = null
			analyzeJob?.cancel("No active files")
		}
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentSelected(event: DocumentSelectedEvent) {
		if (!DocumentUtils.isKotlinFile(event.selectedFile)) {
			return
		}

		selectedFile = event.selectedFile
		val uri = event.selectedFile.toUri().toString()

		log.debug("onDocumentSelected: uri={}", uri)
	}
}
