package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isSourceModule
import com.itsaky.androidide.lsp.kotlin.compiler.services.JavaModuleAccessibilityChecker
import com.itsaky.androidide.lsp.kotlin.compiler.services.JavaModuleAnnotationsProvider
import com.itsaky.androidide.lsp.kotlin.compiler.services.KtLspService
import com.itsaky.androidide.lsp.kotlin.compiler.services.WriteAccessGuard
import com.itsaky.androidide.lsp.kotlin.compiler.services.latestLanguageVersionSettings
import com.itsaky.androidide.lsp.kotlin.compiler.util.SLF4JLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAccessibilityChecker
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAnnotationsProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.AnalysisApiSimpleServiceRegistrar
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
import org.jetbrains.kotlin.cli.jvm.compiler.setupIdeaStandaloneExecution
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
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import org.jetbrains.kotlin.com.intellij.openapi.roots.PackageIndex
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.ClassTypePointerFactory
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
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds

/**
 * Base class shared by [CompilationEnvironment] (production) and the test-only
 * `KtLspTestEnvironment`.  Handles all IntelliJ / Analysis API infrastructure
 * that is identical in both environments:
 */
@OptIn(K1Deprecation::class)
internal abstract class AbstractCompilationEnvironment(
	val name: String,
	val kind: CompilationKind,
	val intellijPluginRoot: Path,
	val jdkHome: Path,
	val jdkRelease: Int,
	val languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
	val applicationEnvironmentMode: KotlinCoreApplicationEnvironmentMode,
	val enableParserEventSystem: Boolean = true,
) : AutoCloseable {
	companion object {
		/** Max time close() will block the (main) thread draining background workers before disposal. */
		val CLOSE_DRAIN_TIMEOUT = 2.seconds

		init {
			System.setProperty("java.awt.headless", "true")
			setupIdeaStandaloneExecution()

			Logger.setFactory { name -> SLF4JLogger(name) }
		}
	}

	protected val disposable = Disposer.newDisposable("CompilationEnvironment[$name]")

	lateinit var projectEnv: KotlinCoreProjectEnvironment
	val applicationEnv: KotlinCoreApplicationEnvironment
		get() = projectEnv.environment as KotlinCoreApplicationEnvironment
	val application: MockApplication
		get() = applicationEnv.application
	val project: MockProject
		get() = projectEnv.project

	lateinit var modules: List<KtModule>
	lateinit var libraryRoots: List<JavaRoot>
	lateinit var ktSymbolIndex: KtSymbolIndex
	lateinit var parser: KtPsiFactory

	val psiManager: PsiManager
		get() = PsiManager.getInstance(project)

	protected abstract fun createServiceRegistrars(): List<AnalysisApiSimpleServiceRegistrar>

	/**
	 * Wires platform services to [ktSymbolIndex], [modules], and [libraryRoots]
	 * via [KtLspService.setupWith].  The default implementation calls [KtLspService.setupWith]
	 * for all standard Analysis API services.
	 */
	protected open fun setupServices(libraryRoots: List<JavaRoot>) {
		listOf(
			KotlinModuleDependentsProvider::class.java,
			KotlinProjectStructureProvider::class.java,
			KotlinPackageProviderFactory::class.java,
			KotlinDeclarationProviderFactory::class.java,
			KotlinPackagePartProviderFactory::class.java,
			KotlinAnnotationsResolverFactory::class.java,
			KotlinDirectInheritorsProvider::class.java,
		).forEach { svcClass ->
			(project.getService(svcClass) as KtLspService).setupWith(
				project = project,
				index = ktSymbolIndex,
				modules = modules,
				libraryRoots = libraryRoots,
			)
		}
	}

	/** Called at the end of [initialize]. Production uses this to start background indexing. */
	protected open fun postInit(libraryRoots: List<JavaRoot>) {}

	/** The [MessageCollector] used by the [CompilerConfiguration]. Defaults to no-op. */
	protected open fun createMessageCollector(): MessageCollector =
		object : MessageCollector {
			override fun clear() {}

			override fun hasErrors() = false

			override fun report(
				severity: CompilerMessageSeverity,
				message: String,
				location: CompilerMessageSourceLocation?,
			) {
			}
		}

	@Suppress("UnstableApiUsage")
	protected open fun initialize(
		buildModules: (
			project: MockProject,
			applicationEnv: KotlinCoreApplicationEnvironment,
		) -> List<KtModule>,
		buildKtSymbolIndex: (
			modules: List<KtModule>,
			libraryRoots: List<JavaRoot>,
		) -> KtSymbolIndex,
	) {
		val configuration = createCompilerConfiguration()
		projectEnv =
			StandaloneProjectFactory.createProjectEnvironment(
				projectDisposable = disposable,
				applicationEnvironmentMode = applicationEnvironmentMode,
				compilerConfiguration = configuration,
			)

		if (applicationEnvironmentMode == KotlinCoreApplicationEnvironmentMode.Production) {
			KotlinApplicationEnvironmentPin.ensure(configuration)
		}

		project.registerRWLock()

		val serviceRegistrars = createServiceRegistrars()
		ApplicationServiceRegistration.registerWithCustomRegistration(
			application,
			serviceRegistrars,
		) {
			registerApplicationServices(application, data = Unit)
		}

		KotlinCoreEnvironment.registerProjectExtensionPoints(project.extensionArea)

		val appExtArea = application.extensionArea
		CoreApplicationEnvironment.registerExtensionPoint(
			appExtArea,
			ClassTypePointerFactory.EP_NAME,
			ClassTypePointerFactory::class.java,
		)

		val classTypePointerFactoryEp =
			appExtArea.getExtensionPoint(ClassTypePointerFactory.EP_NAME)
		if (classTypePointerFactoryEp.extensionList.isEmpty()) {
			classTypePointerFactoryEp.registerExtension(
				PsiClassReferenceTypePointerFactory(),
				application,
			)
		}

		CoreApplicationEnvironment.registerExtensionPoint(
			appExtArea,
			DocumentWriteAccessGuard.EP_NAME,
			WriteAccessGuard::class.java,
		)

		modules = buildModules(project, applicationEnv)

		serviceRegistrars.registerProjectExtensionPoints(project, data = Unit)
		serviceRegistrars.registerProjectServices(project, data = Unit)
		serviceRegistrars.registerProjectModelServices(project, disposable, data = Unit)

		libraryRoots =
			modules
				.asFlatSequence()
				.filterNot { it.isSourceModule }
				.flatMap { lib ->
					lib.computeFiles(extended = false).map { JavaRoot(it, JavaRoot.RootType.BINARY) }
				}.toList()

		ktSymbolIndex = buildKtSymbolIndex(modules, libraryRoots)

		val librariesScope = ProjectScope.getLibrariesScope(project)
		val javaFileManager =
			project.getService(JavaFileManager::class.java) as KotlinCliJavaFileManagerImpl
		val javaModuleFinder =
			CliJavaModuleFinder(jdkHome.toFile(), null, javaFileManager, project, jdkRelease)
		val javaModuleGraph = JavaModuleGraph(javaModuleFinder)
		val delegateJavaModuleResolver =
			CliJavaModuleResolver(javaModuleGraph, emptyList(), emptyList(), project)

		val corePackageIndex = project.getService(PackageIndex::class.java) as CorePackageIndex
		val packagePartProvider =
			JvmPackagePartProvider(latestLanguageVersionSettings, librariesScope).apply {
				addRoots(libraryRoots, MessageCollector.NONE)
			}

		val (javaSourceRoots, singleJavaFileRoots) =
			modules
				.asFlatSequence()
				.filter { it.isSourceModule }
				.flatMap { it.contentRoots }
				.mapNotNull { VirtualFileManager.getInstance().findFileByNioPath(it) }
				.partition { it.isDirectory || it.extension != JavaFileType.DEFAULT_EXTENSION }

		val rootsIndex =
			JvmDependenciesDynamicCompoundIndex(shouldOnlyFindFirstClass = true).apply {
				addIndex(
					JvmDependenciesIndexImpl(
						libraryRoots +
							javaSourceRoots.map {
								JavaRoot(
									it,
									JavaRoot.RootType.SOURCE,
								)
							},
						shouldOnlyFindFirstClass = true,
					),
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
			singleJavaFileRootsIndex =
				SingleJavaFileRootsIndex(
					singleJavaFileRoots.map { JavaRoot(it, JavaRoot.RootType.SOURCE) },
				),
			usePsiClassFilesReading = true,
			perfManager = null,
		)

		val fileFinderFactory = CliVirtualFileFinderFactory(rootsIndex, false, perfManager = null)
		with(project) {
			registerService(
				KotlinJavaModuleAccessibilityChecker::class.java,
				JavaModuleAccessibilityChecker(delegateJavaModuleResolver),
			)
			registerService(
				KotlinJavaModuleAnnotationsProvider::class.java,
				JavaModuleAnnotationsProvider(delegateJavaModuleResolver),
			)
			registerService(VirtualFileFinderFactory::class.java, fileFinderFactory)
			registerService(
				MetadataFinderFactory::class.java,
				CliMetadataFinderFactory(fileFinderFactory),
			)
		}

		setupServices(libraryRoots)

		parser = KtPsiFactory(project, eventSystemEnabled = enableParserEventSystem)
		ktSymbolIndex.parser = parser

		postInit(libraryRoots)
	}

	private fun createCompilerConfiguration(): CompilerConfiguration =
		CompilerConfiguration().apply {
			this.moduleName = JvmProtoBufUtil.DEFAULT_MODULE_NAME
			this.useFir = true
			this.intellijPluginRoot =
				this@AbstractCompilationEnvironment.intellijPluginRoot.pathString
			this.languageVersionSettings =
				LanguageVersionSettingsImpl(
					languageVersion = this@AbstractCompilationEnvironment.languageVersion,
					apiVersion = ApiVersion.createByLanguageVersion(this@AbstractCompilationEnvironment.languageVersion),
					analysisFlags = emptyMap(),
					specificFeatures = LanguageFeature.entries.associateWith { LanguageFeature.State.ENABLED },
				)
			this.jdkHome = this@AbstractCompilationEnvironment.jdkHome.toFile()
			this.jdkRelease = this@AbstractCompilationEnvironment.jdkRelease
			this.messageCollector = createMessageCollector()
		}

	override fun close() {
		// Stop and join the background index workers *before* the project is disposed.
		// Otherwise IndexWorker's coroutine keeps calling PsiManager.findFile(project) on a
		// disposed project and crashes with "AssertionError: Project is already disposed"
		// (Sentry APPDEVFORALL-17R / ADFA-4384). close() runs on the main thread during editor
		// teardown, so the join is bounded by a timeout to avoid an ANR if a read is slow; the
		// project.isDisposed guards cover the rare case where the timeout fires before draining.
		if (::ktSymbolIndex.isInitialized) {
			runBlocking { withTimeoutOrNull(CLOSE_DRAIN_TIMEOUT) { ktSymbolIndex.close() } }
		}

		Disposer.dispose(disposable)
	}
}
