package com.itsaky.androidide.lsp.kotlin.fixtures

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.CompilationKind
import com.itsaky.androidide.lsp.kotlin.compiler.DEFAULT_LANGUAGE_VERSION
import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.buildKtLibraryModule
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.compiler.registrar.AnalysisApiServiceProviders
import com.itsaky.androidide.lsp.kotlin.compiler.registrar.LspAnalysisApiServiceRegistrar
import com.itsaky.androidide.lsp.kotlin.compiler.services.AnalysisPermissionOptions
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.AnalysisApiSimpleServiceRegistrar
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.writeText
import org.jetbrains.kotlin.analysis.api.analyze as ktAnalyze

/**
 * A self-contained Kotlin Analysis API environment for use in plain JVM unit tests.
 *
 * @param sourceRoots Directories containing Kotlin/Java source files for the test.
 * @param extraLibraryJars Additional JARs to add as library modules.
 * @param languageVersion Kotlin language version; defaults to [DEFAULT_LANGUAGE_VERSION].
 * @param jdkRelease JDK release version; defaults to the host JVM's feature version.
 */
@OptIn(K1Deprecation::class, KaImplementationDetail::class)
internal class KtLspTestEnvironment(
	val sourceRoots: List<Path>,
	private val extraLibraryJars: List<Path> = emptyList(),
	languageVersion: LanguageVersion = DEFAULT_LANGUAGE_VERSION,
	jdkRelease: Int = checkNotNull(System.getProperty("java.specification.version")).toInt(),
) : AbstractCompilationEnvironment(
	name = "test",
	kind = CompilationKind.Default,
	intellijPluginRoot = findIntellijPluginRoot(),
	jdkHome = Path.of(System.getProperty("java.home")),
	jdkRelease = jdkRelease,
	languageVersion = languageVersion,
	applicationEnvironmentMode = KotlinCoreApplicationEnvironmentMode.UnitTest,
	enableParserEventSystem = false,
) {
	private lateinit var localFileSystem: CoreLocalFileSystem

	init {
		initialize(::buildModules, ::buildKtSymbolIndex)
	}

	override fun createServiceRegistrars(): List<AnalysisApiSimpleServiceRegistrar> {
		return listOf(
			LspAnalysisApiServiceRegistrar(
				provider = AnalysisApiServiceProviders.Production
					.toBuilder()
					.apply {
						appService(KotlinAnalysisPermissionOptions::class, replace = true) {
							AnalysisPermissionOptions(defaultIsAnalysisAllowedOnEdt = true)
						}
					}
					.build()
			)
		)
	}

	override fun postInit(libraryRoots: List<JavaRoot>) {
		super.postInit(libraryRoots)
		localFileSystem = applicationEnv.localFileSystem
	}

	private fun buildModules(
		project: MockProject,
		applicationEnv: KotlinCoreApplicationEnvironment,
	): List<KtModule> {
		val jdkModule = buildKtLibraryModule(project, applicationEnv) {
			id = "jdk"
			isSdk = true
			addContentRoot(jdkHome)
		}

		val stdlibModule = findKotlinStdlibJar()?.let { jar ->
			buildKtLibraryModule(project, applicationEnv) {
				id = jar.pathString
				addContentRoot(jar)
				addDependency(jdkModule)
			}
		}

		val extraLibModules = extraLibraryJars.map { jar ->
			buildKtLibraryModule(project, applicationEnv) {
				id = jar.pathString
				addContentRoot(jar)
				addDependency(jdkModule)
				stdlibModule?.let { addDependency(it) }
			}
		}

		val sourceDeps: List<KtModule> = buildList {
			add(jdkModule)
			stdlibModule?.let { add(it) }
			addAll(extraLibModules)
		}

		val sourceModules = sourceRoots.mapIndexed { i, root ->
			TestKtSourceModule(
				project = project,
				name = "test-source-$i",
				roots = setOf(root),
				dependencies = sourceDeps,
				languageVersion = languageVersion,
			)
		}

		return buildList {
			addAll(sourceModules)
			stdlibModule?.let { add(it) }
			addAll(extraLibModules)
			add(jdkModule)
		}
	}

	private fun buildKtSymbolIndex(
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>,
	): KtSymbolIndex {
		val inMemoryJvmBackingIndex = InMemoryIndex(JvmSymbolDescriptor)
		val inMemoryJvmSymbolIndex = object : JvmSymbolIndex(inMemoryJvmBackingIndex, BackgroundIndexer(inMemoryJvmBackingIndex)) {
			// ensure we're not filtering out anything
			override fun isActive(sourceId: String) = true
		}

		val inMemoryFileMetaBackingIndex = InMemoryIndex(KtFileMetadataDescriptor)
		val inMemoryFileMetaIndex = KtFileMetadataIndex(inMemoryFileMetaBackingIndex)

		return KtSymbolIndex(
			kind = kind,
			project = project,
			modules = modules,
			fileIndex = inMemoryFileMetaIndex,
			sourceIndex = inMemoryJvmSymbolIndex,
			libraryIndex = inMemoryJvmSymbolIndex,
		)
	}

	/**
	 * Writes [content] to [relativePath] under the first source root, refreshes
	 * the VFS, and returns the corresponding [KtFile].
	 */
	fun createSourceFile(
		relativePath: String,
		content: String,
	): KtFile {
		require(sourceRoots.isNotEmpty()) { "No source roots configured" }
		val file = sourceRoots.first().resolve(relativePath)
		file.parent.toFile().mkdirs()
		file.writeText(content)

		val vf = localFileSystem.refreshAndFindFileByPath(file.pathString)
			?: error("VFS cannot find newly created file: $file")

		modules.filterIsInstance<TestKtSourceModule>().forEach { it.invalidateSearchScope() }

		return project.read {
			PsiManager.getInstance(project).findFile(vf) as? KtFile
				?: error("PSI file not found for: $file")
		}
	}

	/**
	 * Runs [action] inside a [KaSession] for [file], acquiring the project read lock first.
	 */
	inline fun <R> analyze(file: KtFile, crossinline action: KaSession.() -> R): R =
		project.read { ktAnalyze(file, action) }
}

/**
 * Locates the kotlin-android embeddable JAR that serves as the IntelliJ plugin
 * root for the Analysis API.
 *
 * The JAR is cached by the `externalAssets` Gradle plugin under the name
 * `kt-android.jar` (derived from `jarDependency("kt-android")`).  We find it
 * on the test classpath by name, which works reliably under both plain-JVM and
 * Robolectric classloaders.  A reflection-based fallback handles any environment
 * where the JAR name differs.
 */
private fun findIntellijPluginRoot(): Path {
	// Primary: scan the classpath for the well-known cached names.
	val classPath = System.getProperty("java.class.path") ?: ""
	classPath.split(java.io.File.pathSeparator)
		.map { Path.of(it) }
		.firstOrNull {
			// named 'kt-android.jar' when added by external assets plugins
			it.name == "kt-android.jar" ||

					// for local builds, named 'analysis-api-standalone-embeddable-for-ide-X.X.X-SNAPSHOT.jar'
					it.name.matches("analysis-api-standalone-embeddable-for-ide.*\\.jar".toRegex())
		}
		?.let { return it }

	// Fallback to reflection. This works on a plain JVM where the classloader exposes
	// the code source, but may not work under Robolectric.
	return try {
		val location = StandaloneProjectFactory::class.java.protectionDomain
			?.codeSource?.location
			?: error("code source is null")
		val path = Path.of(location.toURI())
		check(path.name.endsWith(".jar")) { "resolved to directory, not a JAR: $path" }
		path
	} catch (e: Exception) {
		error(
			"Cannot locate kt-android.jar on the test classpath. " +
					"Ensure the subprojects.kotlinAnalysisApi dependency is included in testImplementation. " +
					"Also verify that the JAR file name matches expected names. " +
					"(reflection fallback also failed: ${e.message})"
		)
	}
}

private fun findKotlinStdlibJar(): Path? = try {
	val location = KotlinVersion::class.java.protectionDomain?.codeSource?.location ?: return null
	Path.of(location.toURI()).takeIf { it.name.endsWith(".jar") }
} catch (_: Exception) {
	null
}
