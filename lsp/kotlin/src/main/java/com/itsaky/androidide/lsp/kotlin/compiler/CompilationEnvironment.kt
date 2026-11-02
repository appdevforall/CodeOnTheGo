package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AbstractKtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPreemptedException
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.registrar.AnalysisApiServiceProviders
import com.itsaky.androidide.lsp.kotlin.compiler.registrar.LspAnalysisApiServiceRegistrar
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import com.itsaky.androidide.lsp.kotlin.compiler.services.ResolutionScopeProvider
import com.itsaky.androidide.lsp.kotlin.diagnostic.collectDiagnosticsFor
import com.itsaky.androidide.lsp.kotlin.utils.SymbolVisibilityChecker
import com.itsaky.androidide.projects.FileManager
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
import org.appdevforall.codeonthego.indexing.jvm.JVM_LIBRARY_SYMBOL_INDEX
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KT_SOURCE_FILE_INDEX_KEY
import org.appdevforall.codeonthego.indexing.jvm.KT_SOURCE_FILE_META_INDEX_KEY
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.config.LanguageVersion
import org.slf4j.LoggerFactory
import java.nio.file.Path
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
	private val layout: KotlinModuleLayout,
	val ktProject: KotlinProjectModel,
	intellijPluginRoot: Path,
	jdkHome: Path,
	jdkRelease: Int,
	languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
	enableParserEventSystem: Boolean = true,
	val coroutineScope: CoroutineScope =
		CoroutineScope(
			SupervisorJob() + CoroutineName("CompilationEnv[$name]") +
				CoroutineExceptionHandler { _, t ->
					// Defense in depth: swallow (but log) non-cancellation failures from the
					// debounce worker so a ClosedReceiveChannelException can never crash the app.
					if (t !is CancellationException) {
						logger.warn(
							"Uncaught exception in compilation environment coroutine",
							t,
						)
					}
				},
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
	),
	KotlinProjectModel.ProjectModelListener {
	companion object {
		val DEFAULT_FILE_MOD_EVENT_DEBOUNCE_DURATION = 400.milliseconds
		private val logger = LoggerFactory.getLogger(CompilationEnvironment::class.java)
	}

	private var _languageClient: ILanguageClient? = null

	val fileAnalyzer: KeyedDebouncingAction<Path>

	val refreshScheduler: KeyedDebouncingAction<Path>

	val libraryIndex: JvmSymbolIndex?
		get() = ktProject.libraryIndex

	val requireLibraryIndex: JvmSymbolIndex
		get() = ktProject.requireIndex(JVM_LIBRARY_SYMBOL_INDEX)

	val sourceIndex: JvmSymbolIndex?
		get() = ktProject.sourceIndex

	val requireSourceIndex: JvmSymbolIndex
		get() = ktProject.requireIndex(KT_SOURCE_FILE_INDEX_KEY)

	val fileIndex: KtFileMetadataIndex?
		get() = ktProject.fileIndex

	val requireFileIndex: KtFileMetadataIndex
		get() = ktProject.requireIndex(KT_SOURCE_FILE_META_INDEX_KEY)

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
		try {
			initialize(::buildModules, ::buildKtSymbolIndex)
		} catch (failure: Throwable) {
			releaseAfterFailedInit(failure)
			throw failure
		}
	}

	/*
	 * A throwing constructor never hands this instance to anyone who would close it, so the project
	 * environment initialize() already created would leak. super.close() rather than close(): the
	 * fields this class declares after its init block are still unset.
	 */
	private fun releaseAfterFailedInit(failure: Throwable) {
		runCatching { super.close() }.exceptionOrNull()?.let(failure::addSuppressed)
	}

	@OptIn(KaImplementationDetail::class)
	@Suppress("UNUSED_PARAMETER")
	private fun buildKtSymbolIndex(
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>,
	): KtSymbolIndex =
		KtSymbolIndex(
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
	): List<KtModule> = layout.toKtModules(project, applicationEnv)

	override fun createServiceRegistrars() = listOf(LspAnalysisApiServiceRegistrar(AnalysisApiServiceProviders.Production))

	override fun createMessageCollector(): MessageCollector =
		object : MessageCollector {
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
		fileAnalyzer =
			KeyedDebouncingAction(
				scope = coroutineScope,
				debounceDuration = DEFAULT_FILE_MOD_EVENT_DEBOUNCE_DURATION,
			) { path, cancelChecker ->
				try {
					val result = collectDiagnosticsFor(path, cancelChecker)
					withContext(Dispatchers.Main.immediate) {
						languageClient?.publishDiagnostics(result)
					}
				} catch (e: AnalysisPreemptedException) {
					// Preempted by completion; re-schedule so diagnostics still run once it finishes.
					logger.debug("diagnostics for {} preempted; rescheduling", path)
					fileAnalyzer.schedule(path)
				}
			}

		refreshScheduler =
			KeyedDebouncingAction(
				scope = coroutineScope,
				debounceDuration = DEFAULT_FILE_MOD_EVENT_DEBOUNCE_DURATION,
			) { path, _ ->
				// Pull through the cache so a refresh (and its reindex) happens after every edit,
				// independent of whether diagnostics run.
				ktSymbolIndex.refreshCurrentKtFile(path)
			}
	}

	fun refreshSources() {
		Sentry.addBreadcrumb("refreshSources (env=$name, modules=${modules.size})")
		project.writeUnlessDisposed {
			Sentry.addBreadcrumb("refreshSources(env=$name): in-progress")
			ResolutionScopeProvider.getInstance(project).invalidateAll()
			modules
				.asFlatSequence()
				.filterIsInstance<AbstractKtModule>()
				.forEach { it.invalidateSearchScope() }
		}
		ktSymbolIndex.refreshSources()
	}

	fun openFileIfNeeded(path: Path) {
		fileAnalyzer.schedule(path)
	}

	fun onFileOpen(path: Path) {
		fileAnalyzer.schedule(path)
	}

	fun onFileSaved(path: Path) {
		fileAnalyzer.schedule(path)
	}

	fun onFileClosed(path: Path) {
		fileAnalyzer.cancelPending(path)
		refreshScheduler.cancelPending(path)
		ktSymbolIndex.invalidateCurrent(path)
	}

	suspend fun onFileChangedOnDisk(path: Path) {
		if (project.isDisposed || FileManager.isActive(path)) return
		ktSymbolIndex.evictDiskCopy(path)
		notifyElementModifiedForPath(path) { KaElementModificationType.Unknown }
		ktSymbolIndex.submitForIndexing(path)
	}

	suspend fun onFileMoved(
		fromPath: Path,
		toPath: Path,
	) {
		val isFileOpen = FileManager.isActive(fromPath)
		onFileRemoved(fromPath)
		onFileCreated(toPath)
		if (isFileOpen) {
			ktSymbolIndex.invalidateCurrent(fromPath)
			onFileOpen(toPath)
		}
	}

	fun onFileContentChanged(path: Path) {
		refreshScheduler.schedule(path)
		fileAnalyzer.schedule(path)
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
