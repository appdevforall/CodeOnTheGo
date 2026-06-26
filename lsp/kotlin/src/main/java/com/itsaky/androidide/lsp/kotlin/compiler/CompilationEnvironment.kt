package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AbstractKtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.registrar.AnalysisApiServiceProviders
import com.itsaky.androidide.lsp.kotlin.compiler.registrar.LspAnalysisApiServiceRegistrar
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import com.itsaky.androidide.lsp.kotlin.compiler.services.ResolutionScopeProvider
import com.itsaky.androidide.lsp.kotlin.diagnostic.collectDiagnosticsFor
import com.itsaky.androidide.lsp.kotlin.utils.SymbolVisibilityChecker
import com.itsaky.androidide.lsp.kotlin.utils.toVirtualFileOrNull
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.utils.KeyedDebouncingAction
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.platform.analysisMessageBus
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleStateModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleStateModificationKind
import org.jetbrains.kotlin.analysis.api.platform.modification.publishModificationEvent
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationTopics
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.milliseconds

/**
 * A compilation environment for compiling Kotlin sources.
 *
 * @param intellijPluginRoot The IntelliJ plugin root. This is usually the embeddable JAR location. Required.
 * @param languageVersion The language version this environment should target.
 * @param jdkHome Path to the JDK installation directory.
 * @param jdkRelease The JDK release version at [jdkHome].
 */
internal class CompilationEnvironment(
	name: String,
	kind: CompilationKind,
	private val workspace: Workspace,
	val ktProject: KotlinProjectModel,
	intellijPluginRoot: Path,
	jdkHome: Path,
	jdkRelease: Int,
	languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
	enableParserEventSystem: Boolean = true,
	val coroutineScope: CoroutineScope = CoroutineScope(
		SupervisorJob() + CoroutineName("CompilationEnv[$name]") +
			CoroutineExceptionHandler { _, t ->
				// Defense in depth: swallow (but log) non-cancellation failures from the
				// debounce worker so a ClosedReceiveChannelException can never crash the app.
				if (t !is CancellationException) {
					logger.warn("Uncaught exception in compilation environment coroutine", t)
				}
			}
	),
) : AbstractCompilationEnvironment(
	name = name,
	kind = kind,
	intellijPluginRoot = intellijPluginRoot,
	jdkHome = jdkHome,
	jdkRelease = jdkRelease,
	languageVersion = languageVersion,
	applicationEnvironmentMode = KotlinCoreApplicationEnvironmentMode.Production,
	enableParserEventSystem = enableParserEventSystem,
), KotlinProjectModel.ProjectModelListener {

	companion object {
		val DEFAULT_FILE_MOD_EVENT_DEBOUNCE_DURATION = 400.milliseconds
		private val logger = LoggerFactory.getLogger(CompilationEnvironment::class.java)
	}

	private var _languageClient: ILanguageClient? = null

	val fileAnalyzer: KeyedDebouncingAction<Path>

	val libraryIndex: JvmSymbolIndex?
		get() = ktProject.libraryIndex

	val requireLibraryIndex: JvmSymbolIndex
		get() = checkNotNull(libraryIndex)

	val sourceIndex: JvmSymbolIndex?
		get() = ktProject.sourceIndex

	val requireSourceIndex: JvmSymbolIndex
		get() = checkNotNull(sourceIndex)

	val fileIndex: KtFileMetadataIndex?
		get() = ktProject.fileIndex

	val requireFileIndex: KtFileMetadataIndex
		get() = checkNotNull(fileIndex)

	val generatedIndex: JvmSymbolIndex?
		get() = ktProject.generatedIndex

	val symbolVisibilityChecker: SymbolVisibilityChecker by lazy {
		SymbolVisibilityChecker(ProjectStructureProvider.getInstance(project))
	}

	var languageClient: ILanguageClient?
		get() = _languageClient
		set(value) {
			_languageClient = value
		}

	init {
		initialize(::buildModules, ::buildKtSymbolIndex)
	}

	@OptIn(KaImplementationDetail::class)
	@Suppress("UNUSED_PARAMETER")
	private fun buildKtSymbolIndex(
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>,
	): KtSymbolIndex = KtSymbolIndex(
		kind = kind,
		project = project,
		modules = modules,
		fileIndex = requireFileIndex,
		sourceIndex = requireSourceIndex,
		libraryIndex = requireLibraryIndex,
	)

	private fun buildModules(
		project: MockProject,
		applicationEnv: KotlinCoreApplicationEnvironment,
	): List<KtModule> = workspace.collectKtModules(project, applicationEnv)

	override fun createServiceRegistrars() =
		listOf(LspAnalysisApiServiceRegistrar(AnalysisApiServiceProviders.Production))

	override fun createMessageCollector(): MessageCollector = object : MessageCollector {
		override fun clear() {}
		override fun hasErrors() = false
		override fun report(
			severity: CompilerMessageSeverity,
			message: String,
			location: CompilerMessageSourceLocation?,
		) {
			logger.info("[{}] {} ({})", severity.name, message, location)
		}
	}

	override fun postInit(libraryRoots: List<JavaRoot>) {
		ktSymbolIndex.syncIndexInBackground()
	}

	init {
		fileAnalyzer = KeyedDebouncingAction(
			scope = coroutineScope,
			debounceDuration = DEFAULT_FILE_MOD_EVENT_DEBOUNCE_DURATION,
		) { path, cancelChecker ->
			val result = collectDiagnosticsFor(path, cancelChecker)
			withContext(Dispatchers.Main.immediate) {
				languageClient?.publishDiagnostics(result)
			}
		}
	}

	fun refreshSources() {
		Sentry.addBreadcrumb("refreshSources (env=${name}, modules=${modules.size})")
		project.write {
			Sentry.addBreadcrumb("refreshSources(env=${name}): in-progress")
			ResolutionScopeProvider.getInstance(project).invalidateAll()
			modules.asFlatSequence()
				.filterIsInstance<AbstractKtModule>()
				.forEach { it.invalidateSearchScope() }
		}
		ktSymbolIndex.refreshSources()
	}

	fun openFileIfNeeded(path: Path) {
		ktSymbolIndex.getOpenedKtFile(path) ?: onFileOpen(path)
	}

	fun onFileOpen(path: Path) {
		val ktFile = loadKtFile(path) ?: return
		ktSymbolIndex.openKtFile(path, ktFile)
		fileAnalyzer.schedule(path)
	}

	fun onFileSaved(path: Path) {
		fileAnalyzer.schedule(path)
	}

	fun onFileClosed(path: Path) {
		fileAnalyzer.cancelPending(path)
		ktSymbolIndex.closeKtFile(path)
		ProjectStructureProvider.getInstance(project).unregisterInMemoryFile(path.pathString)
	}

	@OptIn(KaImplementationDetail::class)
	private inline fun notifyElementModifiedForPath(
		path: Path,
		crossinline typeProvider: (KtFile) -> KaElementModificationType,
	) {
		// Resolve PSI/module structure under the read lock, mirroring loadKtFile(); driving
		// psiManager.findFile / structureProvider concurrently with an `analyze` read section
		// otherwise races.
		val (ktFile, module) = project.read {
			val structureProvider = ProjectStructureProvider.getInstance(project)
			val ktFile = path.toVirtualFileOrNull()?.let {
				psiManager.findFile(it) as? KtFile
			}

			val module = (ktFile?.let { structureProvider.getModule(it, null) }
				?: structureProvider.findModuleForSourceId(path.pathString)) as? AbstractKtModule

			ktFile to module
		}

		project.write {
			// Must run under the write lock so the session mutation can't race a concurrent
			// `analyze` (which only holds the read lock); see onFileContentChanged.
			if (ktFile != null) {
				KaSourceModificationService.getInstance(project)
					.handleElementModification(ktFile, typeProvider(ktFile))
			}

			if (module != null) {
				module.invalidateSearchScope()
				project.publishModificationEvent(
					KotlinModuleStateModificationEvent(
						module,
						KotlinModuleStateModificationKind.UPDATE,
					)
				)
				project.analysisMessageBus
					.syncPublisher(LLFirSessionInvalidationTopics.SESSION_INVALIDATION)
					.afterInvalidation(setOf(module))
				ResolutionScopeProvider.getInstance(project).invalidate(module)
			} else {
				project.analysisMessageBus
					.syncPublisher(LLFirSessionInvalidationTopics.SESSION_INVALIDATION)
					.afterGlobalInvalidation()
				ResolutionScopeProvider.getInstance(project).invalidateAll()
			}
		}
	}

	suspend fun onFileCreated(path: Path) {
		notifyElementModifiedForPath(path) { KaElementModificationType.ElementAdded }
		ktSymbolIndex.submitForIndexing(path)
	}

	suspend fun onFileRemoved(path: Path) {
		notifyElementModifiedForPath(path) { ktFile ->
			KaElementModificationType.ElementRemoved(ktFile)
		}
		ProjectStructureProvider.getInstance(project).unregisterInMemoryFile(path.pathString)
		ktSymbolIndex.removeFromIndex(path)
	}

	suspend fun onFileMoved(fromPath: Path, toPath: Path) {
		val isFileOpen = ktSymbolIndex.getOpenedKtFile(fromPath) != null
		onFileRemoved(fromPath)
		onFileCreated(toPath)
		if (isFileOpen) {
			ktSymbolIndex.closeKtFile(fromPath)
			onFileOpen(toPath)
		}
	}

	fun onFileContentChanged(path: Path) {
		val oldKtFile = ktSymbolIndex.getOpenedKtFile(path)
		val newContent = FileManager.getDocumentContents(path)
		val newKtFile = project.read {
			parser.createFile(path.pathString, newContent)
		}

		newKtFile.backingFilePath = path

		val provider = ProjectStructureProvider.getInstance(project)
		provider.registerInMemoryFile(path.pathString, newKtFile.virtualFile)

		project.write {
			val toInvalidate = oldKtFile ?: newKtFile
			KaSourceModificationService.getInstance(project)
				.handleElementModification(toInvalidate, KaElementModificationType.Unknown)
			ktSymbolIndex.openKtFile(path, newKtFile)
			ktSymbolIndex.queueOnFileChangedAsync(newKtFile)
			fileAnalyzer.schedule(path)
		}
	}

	private fun loadKtFile(path: Path): KtFile? {
		val virtualFile =
			project.read { VirtualFileManager.getInstance().findFileByNioPath(path) } ?: return null
		return project.read { psiManager.findFile(virtualFile) as? KtFile }
	}

	override fun close() {
		ktProject.removeListener(this)

		// fileAnalyzer reads the project (collectDiagnosticsFor). Cancel AND join it before
		// super.close() disposes the project, so an in-flight read can't touch a disposed project
		// (APPDEVFORALL-17R / ADFA-4384). Bounded so a slow read can't block shutdown indefinitely.
		runBlocking {
			withTimeoutOrNull(CLOSE_DRAIN_TIMEOUT) {
				coroutineScope.coroutineContext[Job]?.cancelAndJoin()
			}
		}

		super.close()
	}

	override fun onProjectModelChanged(
		model: KotlinProjectModel,
		changeKind: KotlinProjectModel.ChangeKind,
	) = Unit
}
