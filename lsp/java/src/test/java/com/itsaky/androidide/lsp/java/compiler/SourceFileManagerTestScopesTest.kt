package com.itsaky.androidide.lsp.java.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.lsp.java.JavaCompilerProvider
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.ArtifactScope
import com.itsaky.androidide.utils.Environment
import jdkx.tools.JavaFileObject
import jdkx.tools.StandardLocation
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Java test sources compile in their own scope (ADFA-7115): a unit test sees main and its own
 * sources and jars, an android test likewise, and main sees neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SourceFileManagerTestScopesTest {
	@get:Rule
	val temp = TemporaryFolder()

	private lateinit var mainDir: File
	private lateinit var unitDir: File
	private lateinit var deviceDir: File
	private lateinit var sharedDir: File
	private lateinit var junit: File
	private lateinit var module: AndroidModule
	private var previousJavaHome: File? = null

	// Other test classes in this JVM (JavaLSPTest) sync the test project once and keep relying on it.
	private var previousWorkspace: Workspace? = null

	@Before
	fun setUp() {
		previousJavaHome = Environment.JAVA_HOME
		previousWorkspace = ProjectManagerImpl.getInstance().workspace
		Environment.JAVA_HOME = File(System.getProperty("java.home"))

		mainDir = temp.newFolder("app", "src", "main", "java")
		unitDir = temp.newFolder("app", "src", "test", "java")
		deviceDir = temp.newFolder("app", "src", "androidTest", "java")
		sharedDir = temp.newFolder("app", "src", "sharedTest", "java")
		source(mainDir, "Main")
		source(unitDir, "MainTest")
		source(deviceDir, "DeviceHelper")
		junit = jar("junit.jar", "org/junit/Test.class")
		val libDir = temp.newFolder("lib", "src", "main", "java")
		source(libDir, "Lib")

		module = androidModule()
		val lib = libraryModule(libDir)
		val root =
			GradleProject(
				GradleModels.GradleProject
					.newBuilder()
					.setName("root")
					.setPath(":")
					.build(),
			)
		ProjectManagerImpl.getInstance().workspace =
			Workspace(rootProject = root, subProjects = listOf(module, lib), syncIssues = emptyList())
		module.indexSources()
	}

	@After
	fun tearDown() {
		SourceFileManager.clearCache()
		ProjectManagerImpl.getInstance().workspace = previousWorkspace
		Environment.JAVA_HOME = previousJavaHome
	}

	@Test
	fun `a unit test sees main and its own sources, not android tests`() {
		val unit = SourceFileManager.forModule(module, ArtifactScope.UNIT_TEST)

		assertThat(sourceFor(unit, "Main")).isNotNull()
		assertThat(sourceFor(unit, "MainTest")).isNotNull()
		assertThat(sourceFor(unit, "DeviceHelper")).isNull()
	}

	@Test
	fun `an android test sees main and its own sources, not unit tests`() {
		val device = SourceFileManager.forModule(module, ArtifactScope.ANDROID_TEST)

		assertThat(sourceFor(device, "Main")).isNotNull()
		assertThat(sourceFor(device, "DeviceHelper")).isNotNull()
		assertThat(sourceFor(device, "MainTest")).isNull()
	}

	@Test
	fun `main sees no test sources`() {
		val main = SourceFileManager.forModule(module)

		assertThat(sourceFor(main, "Main")).isNotNull()
		assertThat(sourceFor(main, "MainTest")).isNull()
		assertThat(sourceFor(main, "DeviceHelper")).isNull()
	}

	@Test
	fun `only the unit test scope compiles against a unit test jar`() {
		assertThat(junitClasses(SourceFileManager.forModule(module, ArtifactScope.UNIT_TEST))).isNotEmpty()
		assertThat(junitClasses(SourceFileManager.forModule(module, ArtifactScope.ANDROID_TEST))).isEmpty()
		assertThat(junitClasses(SourceFileManager.forModule(module))).isEmpty()
	}

	@Test
	fun `a test source gets its package in the binary name`() {
		val unit = SourceFileManager.forModule(module, ArtifactScope.UNIT_TEST)

		val binaryName = unit.inferBinaryName(StandardLocation.SOURCE_PATH, sourceFor(unit, "MainTest"))

		assertThat(binaryName).isEqualTo("com.app.MainTest")
	}

	@Test
	fun `a test file belongs to its scope`() {
		assertThat(module.artifactScopeOf(File(unitDir, "com/app/MainTest.java"))).isEqualTo(ArtifactScope.UNIT_TEST)
		assertThat(module.artifactScopeOf(File(deviceDir, "com/app/DeviceHelper.java"))).isEqualTo(ArtifactScope.ANDROID_TEST)
		assertThat(module.artifactScopeOf(File(mainDir, "com/app/Main.java"))).isEqualTo(ArtifactScope.Main)
	}

	@Test
	fun `a created file is indexed only where it is visible`() {
		val manager = ProjectManagerImpl.getInstance()
		val newTest = source(unitDir, "NewTest")
		val newMain = source(mainDir, "NewMain")

		manager.onFileCreated(FileCreationEvent(newTest))
		manager.onFileCreated(FileCreationEvent(newMain))

		assertThat(indexedIn("NewTest")).containsExactly(ArtifactScope.UNIT_TEST)
		assertThat(indexedIn("NewMain")).containsExactly(ArtifactScope.Main, ArtifactScope.UNIT_TEST, ArtifactScope.ANDROID_TEST)

		manager.onFileDeleted(FileDeletionEvent(newMain))

		assertThat(indexedIn("NewMain")).isEmpty()
	}

	@Test
	fun `a file created in a dir shared by both test scopes is indexed in both`() {
		val shared = source(sharedDir, "SharedHelper")

		ProjectManagerImpl.getInstance().onFileCreated(FileCreationEvent(shared))

		assertThat(indexedIn("SharedHelper")).containsExactly(ArtifactScope.UNIT_TEST, ArtifactScope.ANDROID_TEST)
	}

	@Test
	fun `a test-only project dependency's sources are visible to that test scope only`() {
		assertThat(sourceFor(SourceFileManager.forModule(module, ArtifactScope.UNIT_TEST), "Lib")).isNotNull()
		assertThat(sourceFor(SourceFileManager.forModule(module, ArtifactScope.ANDROID_TEST), "Lib")).isNull()
		assertThat(sourceFor(SourceFileManager.forModule(module), "Lib")).isNull()
		// The trie feeds completion and import lookup, which do not fall back to the source path.
		assertThat(indexedIn("Lib")).containsExactly(ArtifactScope.UNIT_TEST)
	}

	@Test
	fun `a directory that only shares a main source root's prefix is not under it`() {
		val sibling = File(mainDir.path + "Test", "com/app/Sibling.java")

		assertThat(module.findSourceRoot(sibling)).isNull()
		assertThat(module.findSourceRoot(File(mainDir, "com/app/Main.java"))).isEqualTo(mainDir.toPath())
	}

	@Test
	fun `a main edit invalidates the test scopes' compiles, not main's`() {
		val previousAndroidJar = Environment.ANDROID_JAR
		Environment.ANDROID_JAR = previousAndroidJar ?: File("android.jar")
		try {
			val provider = JavaCompilerProvider.getInstance()
			val main = provider.forModule(module, ArtifactScope.Main)
			val unit = provider.forModule(module, ArtifactScope.UNIT_TEST)

			provider.invalidateScopesSeeing(module, ArtifactScope.Main)

			assertThat(unit.isStale).isTrue()
			assertThat(main.isStale).isFalse()
		} finally {
			JavaCompilerProvider.getInstance().destroy()
			Environment.ANDROID_JAR = previousAndroidJar
		}
	}

	@Test
	fun `a test fixtures edit invalidates the test scopes' compiles, not main's or its own`() {
		withAndroidJar {
			val provider = JavaCompilerProvider.getInstance()
			val main = provider.forModule(module, ArtifactScope.Main)
			val unit = provider.forModule(module, ArtifactScope.UNIT_TEST)
			val fixtures = provider.forModule(module, ArtifactScope.TestFixtures)

			provider.invalidateScopesSeeing(module, ArtifactScope.TestFixtures)

			assertThat(unit.isStale).isTrue()
			assertThat(main.isStale).isFalse()
			assertThat(fixtures.isStale).isFalse()
		}
	}

	/** The scopes whose trie holds `com.app.[name]`. */
	private fun indexedIn(name: String): List<ArtifactScope> =
		listOf(ArtifactScope.Main, ArtifactScope.UNIT_TEST, ArtifactScope.ANDROID_TEST).filter {
			module.javaSourceClasses(it).findNode("com.app.$name") !=
				null
		}

	private fun sourceFor(
		manager: SourceFileManager,
		name: String,
	): JavaFileObject? = manager.getJavaFileForInput(StandardLocation.SOURCE_PATH, "com.app.$name", JavaFileObject.Kind.SOURCE)

	private fun junitClasses(manager: SourceFileManager) =
		manager.list(StandardLocation.CLASS_PATH, "org.junit", setOf(JavaFileObject.Kind.CLASS), false).toList()

	@Test
	fun `req 7 - a file outside every source set of the selected variant gets no compiler`() {
		val other = source(temp.newFolder("app", "src", "release", "java"), "ReleaseOnly")

		withAndroidJar { assertThat(JavaCompilerProvider.forFile(other)).isNull() }
	}

	/** Runs [block] with an android.jar set, as a compiler needs one, and drops the compilers it made. */
	private fun withAndroidJar(block: () -> Unit) {
		val previousAndroidJar = Environment.ANDROID_JAR
		Environment.ANDROID_JAR = previousAndroidJar ?: File("android.jar")
		try {
			block()
		} finally {
			JavaCompilerProvider.getInstance().destroy()
			Environment.ANDROID_JAR = previousAndroidJar
		}
	}

	private fun source(
		root: File,
		name: String,
	): File =
		File(root, "com/app/$name.java").apply {
			parentFile.mkdirs()
			writeText("package com.app;\n\npublic class $name {}\n")
		}

	private fun jar(
		name: String,
		vararg entries: String,
	): File {
		val jar = temp.newFile(name)
		ZipOutputStream(jar.outputStream()).use { zip ->
			entries.forEach {
				zip.putNextEntry(ZipEntry(it))
				zip.closeEntry()
			}
		}
		return jar
	}

	/** `:lib`, which only the app's unit tests depend on. */
	private fun libraryModule(dir: File): AndroidModule {
		val projectDir = dir.parentFile.parentFile.parentFile
		return AndroidModule(
			GradleModels.GradleProject
				.newBuilder()
				.setName("lib")
				.setPath(":lib")
				.setProjectDirPath(projectDir.path)
				.setBuildDirPath(File(projectDir, "build").path)
				.setBuildScriptPath(File(projectDir, "build.gradle").path)
				.setAndroidProject(
					AndroidModels.AndroidProject
						.newBuilder()
						.setProjectType(AndroidModels.ProjectType.LibraryProject)
						.setMainSourceSet(
							AndroidModels.SourceSetContainer.newBuilder().putSourceProviders(ArtifactScope.Main.key, provider(dir)),
						).setVariantDependencies(
							AndroidModels.VariantDependencies
								.newBuilder()
								.setName("debug")
								.putArtifacts(ArtifactScope.Main.key, AndroidModels.ArtifactDependencies.getDefaultInstance()),
						),
				).build(),
		)
	}

	private fun androidModule(): AndroidModule {
		val junitKey = "junit"
		val libKey = "project:lib"
		val unitGraph =
			AndroidModels.DependencyGraph
				.newBuilder()
				.addKey(junitKey)
				.addNode(AndroidModels.GraphNode.newBuilder().setKeyId(0))
				.addRoot(0)
				.addKey(libKey)
				.addNode(AndroidModels.GraphNode.newBuilder().setKeyId(1))
				.addRoot(1)
		val projectDir = mainDir.parentFile.parentFile.parentFile
		return AndroidModule(
			GradleModels.GradleProject
				.newBuilder()
				.setName("app")
				.setPath(":app")
				.setProjectDirPath(projectDir.path)
				.setBuildDirPath(File(projectDir, "build").path)
				.setBuildScriptPath(File(projectDir, "build.gradle").path)
				.setAndroidProject(
					AndroidModels.AndroidProject
						.newBuilder()
						.setProjectType(AndroidModels.ProjectType.ApplicationProject)
						.addAllSourceSetDirPaths(
							listOf(mainDir, unitDir, deviceDir, sharedDir, File(projectDir, "src/release/java")).map { it.path },
						).setMainSourceSet(
							AndroidModels.SourceSetContainer
								.newBuilder()
								.putSourceProviders(ArtifactScope.Main.key, provider(mainDir))
								.putSourceProviders(ArtifactScope.UNIT_TEST.key, provider(unitDir, sharedDir))
								.putSourceProviders(ArtifactScope.ANDROID_TEST.key, provider(deviceDir, sharedDir)),
						).setVariantDependencies(
							AndroidModels.VariantDependencies
								.newBuilder()
								.setName("debug")
								.putArtifacts(ArtifactScope.Main.key, AndroidModels.ArtifactDependencies.getDefaultInstance())
								.putArtifacts(
									ArtifactScope.UNIT_TEST.key,
									AndroidModels.ArtifactDependencies
										.newBuilder()
										.setCompileGraph(unitGraph)
										.build(),
								).putArtifacts(ArtifactScope.ANDROID_TEST.key, AndroidModels.ArtifactDependencies.getDefaultInstance())
								.putLibraries(
									junitKey,
									AndroidModels.Library
										.newBuilder()
										.setKey(junitKey)
										.setType(AndroidModels.LibraryType.ExternalJavaLibrary)
										.setArtifactPath(junit.path)
										.build(),
								).putLibraries(
									libKey,
									AndroidModels.Library
										.newBuilder()
										.setKey(libKey)
										.setType(AndroidModels.LibraryType.Project)
										.setProjectInfo(
											AndroidModels.ProjectInfo
												.newBuilder()
												.setBuildId(":")
												.setProjectPath(":lib"),
										).build(),
								),
						),
				).build(),
		)
	}

	private fun provider(vararg dirs: File) =
		AndroidModels.SourceProvider
			.newBuilder()
			.addAllJavaDirs(dirs.map { it.path })
			.build()
}
