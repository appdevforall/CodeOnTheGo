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
import com.itsaky.androidide.lsp.kotlin.compiler.KotlinProjectModel
import com.itsaky.androidide.lsp.kotlin.compiler.index.KT_SOURCE_FILE_INDEX_KEY
import com.itsaky.androidide.lsp.kotlin.compiler.index.KT_SOURCE_FILE_META_INDEX_KEY
import com.itsaky.androidide.lsp.kotlin.completion.complete
import com.itsaky.androidide.lsp.kotlin.diagnostic.collectDiagnosticsFor
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
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.Workspace
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
import org.appdevforall.codeonthego.indexing.jvm.JvmLibraryIndexingService
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
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
	private var projectModel: KotlinProjectModel? = null
	private var compiler: Compiler? = null
	private var analyzeJob: Job? = null

	override val serverId: String = SERVER_ID

	override val client: ILanguageClient?
		get() = _client

	val settings: IServerSettings
		get() = _settings ?: KotlinServerSettings.getInstance().also { _settings = it }

	companion object {
		private val ANALYZE_DEBOUNCE_DELAY = 400.milliseconds

		const val SERVER_ID = "ide.lsp.kotlin"
		private val logger = LoggerFactory.getLogger(KotlinLanguageServer::class.java)
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
		logger.info("setupWithProject called, initialized={}", initialized)

		val context = BaseApplication.baseInstance
		val indexingServiceManager = ProjectManagerImpl.getInstance()
			.indexingServiceManager

		val indexingRegistry = indexingServiceManager.registry
		indexingRegistry.register(
			key = KT_SOURCE_FILE_INDEX_KEY,
			index = JvmSymbolIndex.createSqliteIndex(
				context = context,
				dbName = KT_SOURCE_FILE_INDEX_KEY.name,
				indexName = KT_SOURCE_FILE_INDEX_KEY.name,
			)
		)

		indexingRegistry.register(
			key = KT_SOURCE_FILE_META_INDEX_KEY,
			index = KtFileMetadataIndex.create(
				context = context,
				dbName = KT_SOURCE_FILE_META_INDEX_KEY.name
			)
		)

		val jvmLibraryIndexingService =
			indexingServiceManager.getService(JvmLibraryIndexingService.ID) as? JvmLibraryIndexingService?

		jvmLibraryIndexingService?.refresh()

		val jdkHome = Environment.JAVA_HOME.toPath()
		val jdkRelease = IJdkDistributionProvider.DEFAULT_JAVA_RELEASE
		val intellijPluginRoot = Paths.get(context.applicationInfo.sourceDir)

		val jvmTarget = JvmTarget.fromString(IJdkDistributionProvider.DEFAULT_JAVA_VERSION)
			?: JvmTarget.JVM_21

		val jvmPlatform = JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)

		if (!initialized) {
			logger.info("Creating initial analysis session")

			val model = KotlinProjectModel()
			model.update(workspace, jvmPlatform)
			this.projectModel = model

			val compiler = Compiler(
				workspace = workspace,
				projectModel = model,
				intellijPluginRoot = intellijPluginRoot,
				jdkHome = jdkHome,
				jdkRelease = jdkRelease,
				languageVersion = LanguageVersion.LATEST_STABLE,
			)

			this.compiler = compiler
		} else {
			logger.info("Updating project model")

			projectModel?.update(workspace, jvmPlatform)
		}

		initialized = true
		logger.info("Kotlin project initialized")
	}

	override fun complete(params: CompletionParams?): CompletionResult {
		if (params == null) {
			logger.warn("Cannot complete for null params")
			return CompletionResult.EMPTY
		}

		logger.debug("complete(position={}, file={})", params.position, params.file)
		return compiler?.compilationEnvironmentFor(params.file)?.complete(params)
			?: CompletionResult.EMPTY
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
		logger.debug("analyze(file={})", file)

		if (!settings.diagnosticsEnabled() || !settings.codeAnalysisEnabled()) {
			logger.debug(
				"analyze() skipped: diagnosticsEnabled={}, codeAnalysisEnabled={}",
				settings.diagnosticsEnabled(), settings.codeAnalysisEnabled()
			)
			return DiagnosticResult.NO_UPDATE
		}

		if (!DocumentUtils.isKotlinFile(file)) {
			logger.debug("analyze() skipped: not a Kotlin file")
			return DiagnosticResult.NO_UPDATE
		}

		return compiler?.compilationEnvironmentFor(file)?.collectDiagnosticsFor(file)
			?: DiagnosticResult.NO_UPDATE
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentOpen(event: DocumentOpenEvent) {
		if (!DocumentUtils.isKotlinFile(event.openedFile)) {
			return
		}

		compiler?.compilationEnvironmentFor(event.openedFile)?.apply {
			onFileOpen(event.openedFile)
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

		compiler?.compilationEnvironmentFor(event.changedFile)?.apply {
			onFileContentChanged(event.changedFile)
		}

		debouncingAnalyze()
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentClose(event: DocumentCloseEvent) {
		if (!DocumentUtils.isKotlinFile(event.closedFile)) {
			return
		}

		compiler?.compilationEnvironmentFor(event.closedFile)?.apply {
			onFileClosed(event.closedFile)
		}

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

		logger.debug("onDocumentSelected: uri={}", uri)
	}
}
