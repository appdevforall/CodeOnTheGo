package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.kotlin.KtFileManager
import com.itsaky.androidide.lsp.kotlin.completion.SymbolVisibilityChecker
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.modification.KotlinStandaloneModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.cli.common.intellijPluginRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.SimpleModificationTracker
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.jdkHome
import org.jetbrains.kotlin.config.jdkRelease
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.config.useFir
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * A compilation environment for compiling Kotlin sources.
 *
 * @param intellijPluginRoot The IntelliJ plugin root. This is usually the location of the embeddable JAR file. Required.
 * @param languageVersion The language version that this environment should be compatible with.
 * @param jdkHome Path to the JDK installation directory.
 * @param jdkRelease The JDK release version at [jdkHome].
 */
internal class CompilationEnvironment(
	val project: KotlinProjectModel,
	val intellijPluginRoot: Path,
	val jdkHome: Path,
	val jdkRelease: Int,
	val languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
	val enableParserEventSystem: Boolean = true
) : KotlinProjectModel.ProjectModelListener, AutoCloseable {
	private var disposable = Disposer.newDisposable()

	var session: StandaloneAnalysisAPISession
		private set

	var parser: KtPsiFactory
		private set

	var fileManager: KtFileManager
		private set

	val psiManager: PsiManager
		get() = PsiManager.getInstance(session.project)

	val psiDocumentManager: PsiDocumentManager
		get() = PsiDocumentManager.getInstance(session.project)

	val modificationTrackerFactory: KotlinModificationTrackerFactory
		get() = session.project.getService(KotlinModificationTrackerFactory::class.java)

	val coreApplicationEnvironment: CoreApplicationEnvironment
		get() = session.coreApplicationEnvironment

	val symbolVisibilityChecker: SymbolVisibilityChecker?
		get() = project.symbolVisibilityChecker

	private val envMessageCollector = object : MessageCollector {
		override fun clear() {
		}

		override fun report(
			severity: CompilerMessageSeverity,
			message: String,
			location: CompilerMessageSourceLocation?
		) {
			logger.info("[{}] {} ({})", severity.name, message, location)
		}

		override fun hasErrors(): Boolean {
			return false
		}

	}

	companion object {
		private val logger = LoggerFactory.getLogger(CompilationEnvironment::class.java)
	}

	init {
		session = buildSession()
		parser = KtPsiFactory(session.project, eventSystemEnabled = enableParserEventSystem)
		fileManager = KtFileManager(parser, psiManager, psiDocumentManager)

		project.addListener(this)
	}

	private fun buildSession(): StandaloneAnalysisAPISession {
		val configuration = createCompilerConfiguration()

		val session = buildStandaloneAnalysisAPISession(
			projectDisposable = disposable,
			unitTestMode = false,
			compilerConfiguration = configuration,
		) {
			buildKtModuleProvider {
				this@CompilationEnvironment.project.configureModules(this)
			}
		}

		return session
	}

	private fun rebuildSession() {
		logger.info("Rebuilding analysis session")

		disposable.dispose()
		disposable = Disposer.newDisposable()

		session = buildSession()
		parser = KtPsiFactory(session.project, eventSystemEnabled = enableParserEventSystem)

		logger.info("Analysis session rebuilt")
	}

	private fun createCompilerConfiguration(): CompilerConfiguration {
		return CompilerConfiguration().apply {
			this.moduleName = JvmProtoBufUtil.DEFAULT_MODULE_NAME
			this.useFir = true
			this.intellijPluginRoot = this@CompilationEnvironment.intellijPluginRoot.pathString
			this.languageVersionSettings = LanguageVersionSettingsImpl(
				languageVersion = this@CompilationEnvironment.languageVersion,
				apiVersion = ApiVersion.createByLanguageVersion(this@CompilationEnvironment.languageVersion),
				analysisFlags = emptyMap(),
				specificFeatures = LanguageFeature.entries.associateWith { LanguageFeature.State.ENABLED }
			)

			this.jdkHome = this@CompilationEnvironment.jdkHome.toFile()
			this.jdkRelease = this@CompilationEnvironment.jdkRelease

			this.messageCollector = this@CompilationEnvironment.envMessageCollector
		}
	}

	private fun refreshSourceFiles() {
		logger.info("Refreshing source files")

		val project = session.project
		val sourceKtFiles = collectSourceKtFiles()

		ApplicationManager.getApplication().runWriteAction {
			(project as MockProject).apply {
				registerService(
					KotlinAnnotationsResolverFactory::class.java,
					KotlinStandaloneAnnotationsResolverFactory(this, sourceKtFiles)
				)

				val decProviderFactory = KotlinStandaloneDeclarationProviderFactory(
					this,
					session.coreApplicationEnvironment,
					sourceKtFiles
				)
				registerService(
					KotlinDeclarationProviderFactory::class.java,
					decProviderFactory
				)

				registerService(
					KotlinPackageProviderFactory::class.java,
					KotlinStandalonePackageProviderFactory(
						project,
						sourceKtFiles + decProviderFactory.getAdditionalCreatedKtFiles()
					)
				)
			}

			val modificationTrackerFactory =
				project.getService(KotlinModificationTrackerFactory::class.java) as? KotlinStandaloneModificationTrackerFactory?
			val sourceModificationTracker =
				modificationTrackerFactory?.createProjectWideSourceModificationTracker() as? SimpleModificationTracker?
			sourceModificationTracker?.incModificationCount()
		}

		logger.info("Refreshed: {} source KtFiles", sourceKtFiles.size)
	}

	@OptIn(KaExperimentalApi::class)
	private fun collectSourceKtFiles(): List<KtFile> = buildList {
		session.modulesWithFiles.keys.forEach { module ->
			module.psiRoots.forEach { psiRoot ->
				val rootFile = psiRoot.virtualFile ?: return@forEach
				rootFile.refresh(false, false)
				collectKtFilesRecursively(rootFile, this)
			}
		}
	}

	private fun collectKtFilesRecursively(
		dir: VirtualFile,
		files: MutableList<KtFile>
	) {
		dir.children.orEmpty().forEach { child ->
			if (child.isDirectory) {
				collectKtFilesRecursively(child, files)
				return@forEach
			}

			if (child.extension == "kt" || child.extension == "kts") {
				val psiFile = psiManager.findFile(child)
				if (psiFile is KtFile) {
					files.add(psiFile)
				}
			}
		}
	}

	override fun close() {
		fileManager.close()
		project.removeListener(this)
		disposable.dispose()
	}

	override fun onProjectModelChanged(
		model: KotlinProjectModel,
		changeKind: KotlinProjectModel.ChangeKind
	) {
		when (changeKind) {
			KotlinProjectModel.ChangeKind.STRUCTURE -> rebuildSession()
			KotlinProjectModel.ChangeKind.SOURCES -> refreshSourceFiles()
		}
	}
}