package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isSourceModule
import com.itsaky.androidide.lsp.kotlin.compiler.registrar.LspServiceRegistrar
import com.itsaky.androidide.lsp.kotlin.compiler.services.JavaModuleAccessibilityChecker
import com.itsaky.androidide.lsp.kotlin.compiler.services.JavaModuleAnnotationsProvider
import com.itsaky.androidide.lsp.kotlin.compiler.services.KtLspService
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import com.itsaky.androidide.lsp.kotlin.compiler.services.WriteAccessGuard
import com.itsaky.androidide.lsp.kotlin.compiler.services.latestLanguageVersionSettings
import com.itsaky.androidide.lsp.kotlin.utils.SymbolVisibilityChecker
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.projects.api.Workspace
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAccessibilityChecker
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAnnotationsProvider
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.ApplicationServiceRegistration
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.registerProjectExtensionPoints
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.registerProjectModelServices
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.registerProjectServices
import org.jetbrains.kotlin.cli.common.intellijPluginRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.CliMetadataFinderFactory
import org.jetbrains.kotlin.cli.jvm.compiler.CliVirtualFileFinderFactory
import org.jetbrains.kotlin.cli.jvm.compiler.JvmPackagePartProvider
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCliJavaFileManagerImpl
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesDynamicCompoundIndex
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.cli.jvm.index.SingleJavaFileRootsIndex
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleFinder
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.cli.jvm.modules.JavaModuleGraph
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.core.CorePackageIndex
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.mock.MockApplication
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import org.jetbrains.kotlin.com.intellij.openapi.roots.PackageIndex
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.ClassTypePointerFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager
import org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.PsiClassReferenceTypePointerFactory
import org.jetbrains.kotlin.com.intellij.psi.search.ProjectScope
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
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
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
@Suppress("UnstableApiUsage")
@OptIn(K1Deprecation::class)
internal class CompilationEnvironment(
	workspace: Workspace,
	val ktProject: KotlinProjectModel,
	val intellijPluginRoot: Path,
	val jdkHome: Path,
	val jdkRelease: Int,
	val languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
	val enableParserEventSystem: Boolean = true
) : KotlinProjectModel.ProjectModelListener, AutoCloseable {
	private var disposable = Disposer.newDisposable()

	val projectEnv: KotlinCoreProjectEnvironment

	val applicationEnv: KotlinCoreApplicationEnvironment
		get() = projectEnv.environment as KotlinCoreApplicationEnvironment

	val application: MockApplication
		get() = applicationEnv.application

	val project: MockProject
		get() = projectEnv.project

	val parser: KtPsiFactory
	val commandProcessor: CommandProcessor
	val modules: List<KtModule>

	val psiManager: PsiManager
		get() = PsiManager.getInstance(project)

	val psiDocumentManager: PsiDocumentManager
		get() = PsiDocumentManager.getInstance(project)

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
		val provider =
			project.getService(KotlinProjectStructureProvider::class.java) as ProjectStructureProvider
		SymbolVisibilityChecker(provider)
	}

	val ktSymbolIndex by lazy {
		KtSymbolIndex(
			project = project,
			modules = modules,
			fileIndex = requireFileIndex,
			sourceIndex = requireSourceIndex,
			libraryIndex = requireLibraryIndex,
		)
	}

	private val serviceRegistrars = listOf(LspServiceRegistrar)

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

		projectEnv = StandaloneProjectFactory
			.createProjectEnvironment(
				projectDisposable = disposable,
				applicationEnvironmentMode = KotlinCoreApplicationEnvironmentMode.Production,
				compilerConfiguration = createCompilerConfiguration(),
			)

		project.registerRWLock()

		ApplicationServiceRegistration.registerWithCustomRegistration(
			application,
			serviceRegistrars,
		) {
			registerApplicationServices(application, data = Unit)
		}

		KotlinCoreEnvironment.registerProjectExtensionPoints(project.extensionArea)

		CoreApplicationEnvironment.registerExtensionPoint(
			application.extensionArea,
			ClassTypePointerFactory.EP_NAME,
			ClassTypePointerFactory::class.java,
		)

		application.extensionArea.getExtensionPoint(ClassTypePointerFactory.EP_NAME)
			.registerExtension(PsiClassReferenceTypePointerFactory(), application)

		CoreApplicationEnvironment.registerExtensionPoint(
			application.extensionArea,
			DocumentWriteAccessGuard.EP_NAME,
			WriteAccessGuard::class.java,
		)

		serviceRegistrars.registerProjectExtensionPoints(project, data = Unit)
		serviceRegistrars.registerProjectServices(project, data = Unit)
		serviceRegistrars.registerProjectModelServices(project, disposable, data = Unit)

		modules = workspace.collectKtModules(project, applicationEnv)

		val librariesScope = ProjectScope.getLibrariesScope(project)
		val libraryRoots = modules
			.asFlatSequence()
			.filterNot { it.isSourceModule }
			.flatMap { libMod ->
				libMod.computeFiles(extended = false)
					.map { file -> JavaRoot(file, JavaRoot.RootType.BINARY) }
			}
			.toList()

		val javaFileManager =
			project.getService(JavaFileManager::class.java) as KotlinCliJavaFileManagerImpl
		val javaModuleFinder =
			CliJavaModuleFinder(jdkHome.toFile(), null, javaFileManager, project, jdkRelease)
		val javaModuleGraph = JavaModuleGraph(javaModuleFinder)
		val delegateJavaModuleResolver =
			CliJavaModuleResolver(javaModuleGraph, emptyList(), emptyList(), project)

		val corePackageIndex = project.getService(PackageIndex::class.java) as CorePackageIndex
		val packagePartProvider = JvmPackagePartProvider(
			latestLanguageVersionSettings,
			librariesScope
		).apply {
			addRoots(libraryRoots, MessageCollector.NONE)
		}

		val (javaRoots, singleJavaFileRoots) = modules
			.asFlatSequence()
			.filter { it.isSourceModule }
			.flatMap { it.contentRoots }
			.mapNotNull { VirtualFileManager.getInstance().findFileByNioPath(it) }
			.partition { it.isDirectory || it.extension != JavaFileType.DEFAULT_EXTENSION }

		val rootsIndex =
			JvmDependenciesDynamicCompoundIndex(shouldOnlyFindFirstClass = true).apply {
				addIndex(
					JvmDependenciesIndexImpl(
						libraryRoots + javaRoots.map { JavaRoot(it, JavaRoot.RootType.SOURCE) },
						shouldOnlyFindFirstClass = true
					)
				)

				indexedRoots.forEach { javaRoot ->
					if (javaRoot.file.isDirectory) {
						if (javaRoot.type == JavaRoot.RootType.SOURCE) {
							javaFileManager.addToClasspath(javaRoot.file)
							corePackageIndex.addToClasspath(javaRoot.file)
						} else {
							projectEnv.addSourcesToClasspath(javaRoot.file)
						}
					}
				}
			}

		javaFileManager.initialize(
			index = rootsIndex,
			packagePartProviders = listOf(packagePartProvider),
			singleJavaFileRootsIndex = SingleJavaFileRootsIndex(singleJavaFileRoots.map {
				JavaRoot(
					it,
					JavaRoot.RootType.SOURCE
				)
			}),
			usePsiClassFilesReading = true,
			perfManager = null,
		)

		val fileFinderFactory = CliVirtualFileFinderFactory(rootsIndex, false, perfManager = null)

		with(project) {
			registerService(
				KotlinJavaModuleAccessibilityChecker::class.java,
				JavaModuleAccessibilityChecker(delegateJavaModuleResolver)
			)
			registerService(
				KotlinJavaModuleAnnotationsProvider::class.java,
				JavaModuleAnnotationsProvider(delegateJavaModuleResolver),
			)
			registerService(VirtualFileFinderFactory::class.java, fileFinderFactory)
			registerService(
				MetadataFinderFactory::class.java,
				CliMetadataFinderFactory(fileFinderFactory)
			)
		}

		// Setup platform services
		val lspServices = listOf(
			KotlinModuleDependentsProvider::class.java,
			KotlinProjectStructureProvider::class.java,
			KotlinPackageProviderFactory::class.java,
			KotlinDeclarationProviderFactory::class.java,
			KotlinPackagePartProviderFactory::class.java,
			KotlinAnnotationsResolverFactory::class.java,
			KotlinDirectInheritorsProvider::class.java,
		)

		for (lspService in lspServices) {
			(project.getService(lspService) as KtLspService).setupWith(
				project = project,
				index = ktSymbolIndex,
				modules = modules,
				libraryRoots = libraryRoots
			)
		}

		commandProcessor = application.getService(CommandProcessor::class.java)
		parser = KtPsiFactory(project, eventSystemEnabled = enableParserEventSystem)

		// Sync the index in the background
		ktSymbolIndex.syncIndexInBackground()
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

	fun onFileOpen(path: Path) {
		val ktFile = loadKtFile(path) ?: return
		ktSymbolIndex.openKtFile(path, ktFile)
	}

	fun onFileClosed(path: Path) {
		ktSymbolIndex.closeKtFile(path)
		(project.getService(KotlinProjectStructureProvider::class.java) as ProjectStructureProvider)
			.unregisterInMemoryFile(path.pathString)
	}

	fun onFileContentChanged(path: Path) {
		val newContent = FileManager.getDocumentContents(path)
		val newKtFile = project.read { parser.createFile(path.pathString, newContent) }
		newKtFile.backingFilePath = path

		// Tell ProjectStructureProvider which module owns this LightVirtualFile.
		val provider =
			project.getService(KotlinProjectStructureProvider::class.java) as ProjectStructureProvider
		provider.registerInMemoryFile(path.pathString, newKtFile.virtualFile)

		ktSymbolIndex.openKtFile(path, newKtFile)
		ktSymbolIndex.queueOnFileChangedAsync(newKtFile)
		project.write {
			KaSourceModificationService.getInstance(project)
				.handleElementModification(newKtFile, KaElementModificationType.Unknown)
		}
	}

	private fun loadKtFile(path: Path): KtFile? {
		val virtualFile =
			project.read { VirtualFileManager.getInstance().findFileByNioPath(path) } ?: return null
		return project.read { psiManager.findFile(virtualFile) as? KtFile }
	}

	override fun close() {
		ktProject.removeListener(this)
		disposable.dispose()
	}

	override fun onProjectModelChanged(
		model: KotlinProjectModel,
		changeKind: KotlinProjectModel.ChangeKind
	) {
	}
}