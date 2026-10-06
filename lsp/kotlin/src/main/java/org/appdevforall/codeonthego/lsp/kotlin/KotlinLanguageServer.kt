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

package org.appdevforall.codeonthego.lsp.kotlin

import org.appdevforall.codeonthego.app.BaseApplication
import org.appdevforall.codeonthego.app.configuration.IJdkDistributionProvider
import org.appdevforall.codeonthego.eventbus.events.BuildCompletedEvent
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentChangeEvent
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentCloseEvent
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentOpenEvent
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentSaveEvent
import org.appdevforall.codeonthego.eventbus.events.file.FileContentChangedEvent
import org.appdevforall.codeonthego.eventbus.events.file.FileCreationEvent
import org.appdevforall.codeonthego.eventbus.events.file.FileDeletionEvent
import org.appdevforall.codeonthego.eventbus.events.file.FileRenameEvent
import org.appdevforall.codeonthego.lsp.api.ILanguageClient
import org.appdevforall.codeonthego.lsp.api.ILanguageServer
import org.appdevforall.codeonthego.lsp.api.IServerSettings
import org.appdevforall.codeonthego.lsp.kotlin.compiler.CompilationEnvironment
import org.appdevforall.codeonthego.lsp.kotlin.compiler.Compiler
import org.appdevforall.codeonthego.lsp.kotlin.compiler.KotlinProjectModel
import org.appdevforall.codeonthego.lsp.kotlin.completion.KotlinSnippetRepository
import org.appdevforall.codeonthego.lsp.kotlin.completion.codeComplete
import org.appdevforall.codeonthego.lsp.kotlin.diagnostic.collectDiagnosticsFor
import org.appdevforall.codeonthego.lsp.kotlin.format.KotlinCodeFormatter
import org.appdevforall.codeonthego.lsp.kotlin.navigation.findDefinitionAt
import org.appdevforall.codeonthego.lsp.kotlin.navigation.findUsagesAt
import org.appdevforall.codeonthego.lsp.kotlin.signaturehelp.doSignatureHelp
import org.appdevforall.codeonthego.lsp.models.CodeFormatResult
import org.appdevforall.codeonthego.lsp.models.CompletionParams
import org.appdevforall.codeonthego.lsp.models.CompletionResult
import org.appdevforall.codeonthego.lsp.models.DefinitionParams
import org.appdevforall.codeonthego.lsp.models.DefinitionResult
import org.appdevforall.codeonthego.lsp.models.DiagnosticResult
import org.appdevforall.codeonthego.lsp.models.ExpandSelectionParams
import org.appdevforall.codeonthego.lsp.models.FormatCodeParams
import org.appdevforall.codeonthego.lsp.models.ReferenceParams
import org.appdevforall.codeonthego.lsp.models.ReferenceResult
import org.appdevforall.codeonthego.lsp.models.SignatureHelp
import org.appdevforall.codeonthego.lsp.models.SignatureHelpParams
import org.appdevforall.codeonthego.lsp.util.LSPEditorActions
import org.appdevforall.codeonthego.models.Range
import org.appdevforall.codeonthego.projects.FileManager
import org.appdevforall.codeonthego.projects.ProjectManagerImpl
import org.appdevforall.codeonthego.projects.api.Workspace
import org.appdevforall.codeonthego.tasks.createJobCancelChecker
import org.appdevforall.codeonthego.utils.DocumentUtils
import org.appdevforall.codeonthego.utils.Environment
import org.appdevforall.codeonthego.utils.ifNotEmpty
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.jvm.JvmLibraryIndexingService
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KT_SOURCE_FILE_INDEX_KEY
import org.appdevforall.codeonthego.indexing.jvm.KT_SOURCE_FILE_META_INDEX_KEY
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension

class KotlinLanguageServer : ILanguageServer {
	private var _client: ILanguageClient? = null
	private var _settings: IServerSettings? = null
	private var initialized = false

	private val scope =
		CoroutineScope(SupervisorJob() + CoroutineName(KotlinLanguageServer::class.simpleName!!))
	private var projectModel: KotlinProjectModel? = null
	private var compiler: Compiler? = null

	override val serverId: String = SERVER_ID

	/**
	 * True for `.kt` only. A `.kts` script is served by this language server but compiles to no
	 * class the debuggee loads, so a breakpoint in one could be drawn and persisted and would then
	 * never bind.
	 */
	override fun supportsDebugging(file: Path): Boolean = file.extension == KOTLIN_SOURCE_EXTENSION

	override val client: ILanguageClient?
		get() = _client

	val settings: IServerSettings
		get() = _settings ?: KotlinServerSettings.getInstance().also { _settings = it }

	companion object {
		const val SERVER_ID = "ide.lsp.kotlin"
		const val KOTLIN_SOURCE_EXTENSION = "kt"
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
		this.compiler?.updateLanguageClient(client)
	}

	/** Returns the [CompilationEnvironment] responsible for [file], or null if the compiler is not ready. */
	internal fun compilationEnvironmentFor(file: Path): CompilationEnvironment? = compiler?.compilationEnvironmentFor(file)

	override fun applySettings(settings: IServerSettings?) {
		this._settings = settings
	}

	override fun setupWithProject(workspace: Workspace) {
		logger.info("setupWithProject called, initialized={}", initialized)

		LSPEditorActions.ensureActionsMenuRegistered(KotlinCodeActionsMenu)

		val context = BaseApplication.baseInstance
		val indexingServiceManager =
			ProjectManagerImpl
				.getInstance()
				.indexingServiceManager

		val indexingRegistry = indexingServiceManager.registry
		indexingRegistry.register(
			key = KT_SOURCE_FILE_INDEX_KEY,
			index =
				JvmSymbolIndex.createSqliteIndex(
					context = context,
					dbName = KT_SOURCE_FILE_INDEX_KEY.name,
					indexName = KT_SOURCE_FILE_INDEX_KEY.name,
				),
		)

		indexingRegistry.register(
			key = KT_SOURCE_FILE_META_INDEX_KEY,
			index =
				KtFileMetadataIndex.sqliteBacked(
					context = context,
					dbName = KT_SOURCE_FILE_META_INDEX_KEY.name,
				),
		)

		val jvmLibraryIndexingService =
			indexingServiceManager.getService(JvmLibraryIndexingService.ID) as? JvmLibraryIndexingService?

		jvmLibraryIndexingService?.refresh()

		val jdkHome = Environment.JAVA_HOME.toPath()
		val jdkRelease = IJdkDistributionProvider.DEFAULT_JAVA_RELEASE
		val intellijPluginRoot = Paths.get(context.applicationInfo.sourceDir)

		val jvmTarget =
			JvmTarget.fromString(IJdkDistributionProvider.DEFAULT_JAVA_VERSION)
				?: JvmTarget.JVM_21

		val jvmPlatform = JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)

		if (!initialized) {
			logger.info("Creating initial analysis session")

			val model = KotlinProjectModel()
			model.update(workspace, jvmPlatform)
			this.projectModel = model

			val compiler =
				Compiler(
					workspace = workspace,
					projectModel = model,
					intellijPluginRoot = intellijPluginRoot,
					jdkHome = jdkHome,
					jdkRelease = jdkRelease,
					languageVersion = LanguageVersion.LATEST_STABLE,
				)

			compiler.updateLanguageClient(client)
			this.compiler = compiler
		} else {
			logger.info("Updating project model")
			projectModel?.update(workspace, jvmPlatform)
		}

		// Open already open files
		// we won't get an event for these
		FileManager.activeDocuments.ifNotEmpty {
			forEach { document ->
				compiler
					?.compilationEnvironmentFor(document.file)
					?.openFileIfNeeded(document.file)
			}
		}

		KotlinSnippetRepository.init()

		initialized = true
		logger.info("Kotlin project initialized")
	}

	override fun complete(params: CompletionParams?): CompletionResult {
		if (params == null) {
			logger.warn("Cannot complete for null params")
			return CompletionResult.EMPTY
		}

		logger.debug("complete(position={}, file={})", params.position, params.file)
		return compiler
			?.compilationEnvironmentFor(params.file)
			?.let { context(it) { codeComplete(params) } }
			?: CompletionResult.EMPTY
	}

	override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
		if (!settings.referencesEnabled()) {
			return ReferenceResult.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return ReferenceResult.empty()
		}

		logger.debug("findReferences(position={}, file={})", params.position, params.file)
		return compiler
			?.compilationEnvironmentFor(params.file)
			?.let { context(it) { findUsagesAt(params) } }
			?: ReferenceResult.empty()
	}

	override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
		if (!settings.definitionsEnabled()) {
			return DefinitionResult.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return DefinitionResult.empty()
		}

		logger.debug("findDefinition(position={}, file={})", params.position, params.file)
		return compiler
			?.compilationEnvironmentFor(params.file)
			?.let { context(it) { findDefinitionAt(params) } }
			?: DefinitionResult.empty()
	}

	override suspend fun expandSelection(params: ExpandSelectionParams): Range = params.selection

	override fun formatCode(params: FormatCodeParams?): CodeFormatResult = KotlinCodeFormatter.format(requireNotNull(params).content)

	override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
		if (!settings.signatureHelpEnabled()) {
			return SignatureHelp.empty()
		}

		if (!DocumentUtils.isKotlinFile(params.file)) {
			return SignatureHelp.empty()
		}

		logger.debug("signatureHelp(position={}, file={})", params.position, params.file)
		return compiler
			?.compilationEnvironmentFor(params.file)
			?.let { context(it) { doSignatureHelp(params) } }
			?: SignatureHelp.empty()
	}

	override suspend fun analyze(file: Path): DiagnosticResult {
		logger.debug("analyze(file={})", file)

		if (!settings.diagnosticsEnabled() || !settings.codeAnalysisEnabled()) {
			logger.debug(
				"analyze() skipped: diagnosticsEnabled={}, codeAnalysisEnabled={}",
				settings.diagnosticsEnabled(),
				settings.codeAnalysisEnabled(),
			)
			return DiagnosticResult.NO_UPDATE
		}

		if (!DocumentUtils.isKotlinFile(file)) {
			logger.debug("analyze() skipped: not a Kotlin file")
			return DiagnosticResult.NO_UPDATE
		}

		return compiler
			?.compilationEnvironmentFor(file)
			?.let { context(it) { collectDiagnosticsFor(file, createJobCancelChecker()) } }
			?: DiagnosticResult.NO_UPDATE
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentOpen(event: DocumentOpenEvent) {
		if (!DocumentUtils.isKotlinFile(event.openedFile)) {
			return
		}

		compiler
			?.compilationEnvironmentFor(event.openedFile)
			?.onFileOpen(event.openedFile)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentChange(event: DocumentChangeEvent) {
		if (!DocumentUtils.isKotlinFile(event.changedFile)) {
			return
		}

		compiler
			?.compilationEnvironmentFor(event.changedFile)
			?.onFileContentChanged(event.changedFile)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentClose(event: DocumentCloseEvent) {
		if (!DocumentUtils.isKotlinFile(event.closedFile)) {
			return
		}

		compiler
			?.compilationEnvironmentFor(event.closedFile)
			?.onFileClosed(event.closedFile)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("unused")
	fun onDocumentSaved(event: DocumentSaveEvent) {
		if (!DocumentUtils.isKotlinFile(event.savedFile)) {
			return
		}

		compiler
			?.compilationEnvironmentFor(event.savedFile)
			?.onFileSaved(event.savedFile)
	}

	@Subscribe
	@Suppress("unused")
	fun onBuildCompleted(event: BuildCompletedEvent) {
		Sentry.addBreadcrumb("onBuildCompleted: result=${event.result}")
		compiler?.refreshSources()
	}

	@Subscribe
	@Suppress("unused")
	fun onFileCreated(event: FileCreationEvent) {
		val path = event.file.toPath()
		if (!DocumentUtils.isKotlinFile(path)) {
			return
		}

		scope.launch {
			runCatching { compiler?.compilationEnvironmentFor(path) }
				.getOrNull()
				?.onFileCreated(path)
		}
	}

	@Subscribe
	@Suppress("unused")
	fun onFileContentChanged(event: FileContentChangedEvent) {
		val path = event.file.toPath()
		if (!DocumentUtils.isKotlinFile(path)) {
			return
		}

		scope.launch {
			runCatching { compiler?.compilationEnvironmentFor(path) }
				.getOrNull()
				?.onFileChangedOnDisk(path)
		}
	}

	@Subscribe
	@Suppress("unused")
	fun onFileDeleted(event: FileDeletionEvent) {
		val path = event.file.toPath()
		if (!DocumentUtils.isKotlinFile(path)) {
			return
		}

		scope.launch {
			runCatching { compiler?.compilationEnvironmentFor(path) }
				.getOrNull()
				?.onFileRemoved(path)
		}
	}

	@Subscribe
	@Suppress("unused")
	fun onFileRenamed(event: FileRenameEvent) {
		val fromPath = event.file.toPath()
		val toPath = event.newFile.toPath()

		scope.launch {
			val oldIsKotlinFile = DocumentUtils.isKotlinFile(fromPath)
			val newIsKotlinFile = DocumentUtils.isKotlinFile(toPath)

			if (!oldIsKotlinFile && newIsKotlinFile) {
				// only the new file is a Kotlin file
				// so just submit it for indexing
				compiler
					?.compilationEnvironmentFor(toPath)
					?.onFileCreated(toPath)
				return@launch
			}

			if (oldIsKotlinFile && !newIsKotlinFile) {
				// only the old file was a Kotlin file
				// so just remove it from the index
				compiler
					?.compilationEnvironmentFor(fromPath)
					?.onFileRemoved(fromPath)
				return@launch
			}

			val fromKind = runCatching { compiler?.compilationKindFor(fromPath) }.getOrNull()
			val toKind = runCatching { compiler?.compilationKindFor(toPath) }.getOrNull()
			val fromEnv = fromKind?.let { compiler?.compilationEnvironmentFor(it) }
			val toEnv = toKind?.let { compiler?.compilationEnvironmentFor(it) }

			if (fromKind != null && fromEnv == toEnv && toEnv != null) {
				// file was renamed within the same compilation environment
				toEnv.onFileMoved(fromPath, toPath)
				return@launch
			}

			// file may have been moved from one compilation environment to another
			// remove from old env's index
			// and submit to the new env for indexing
			fromEnv?.onFileRemoved(fromPath)
			toEnv?.onFileCreated(toPath)
		}
	}
}
