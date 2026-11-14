package com.itsaky.androidide.lsp.java.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.TestScope
import com.itsaky.androidide.projects.api.Workspace
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
	private lateinit var junit: File
	private lateinit var module: AndroidModule
	private var previousJavaHome: File? = null

	@Before
	fun setUp() {
		previousJavaHome = Environment.JAVA_HOME
		Environment.JAVA_HOME = File(System.getProperty("java.home"))

		mainDir = temp.newFolder("app", "src", "main", "java")
		unitDir = temp.newFolder("app", "src", "test", "java")
		deviceDir = temp.newFolder("app", "src", "androidTest", "java")
		source(mainDir, "Main")
		source(unitDir, "MainTest")
		source(deviceDir, "DeviceHelper")
		junit = jar("junit.jar", "org/junit/Test.class")

		module = androidModule()
		val root =
			GradleProject(
				GradleModels.GradleProject
					.newBuilder()
					.setName("root")
					.setPath(":")
					.build(),
			)
		ProjectManagerImpl.getInstance().workspace = Workspace(rootProject = root, subProjects = listOf(module), syncIssues = emptyList())
		module.indexSources()
	}

	@After
	fun tearDown() {
		SourceFileManager.clearCache()
		runCatching { ProjectManagerImpl.getInstance().workspace = null }
		Environment.JAVA_HOME = previousJavaHome
	}

	@Test
	fun `a unit test sees main and its own sources, not android tests`() {
		val unit = SourceFileManager.forModule(module, TestScope.UNIT)

		assertThat(sourceFor(unit, "Main")).isNotNull()
		assertThat(sourceFor(unit, "MainTest")).isNotNull()
		assertThat(sourceFor(unit, "DeviceHelper")).isNull()
	}

	@Test
	fun `an android test sees main and its own sources, not unit tests`() {
		val device = SourceFileManager.forModule(module, TestScope.ANDROID)

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
		assertThat(junitClasses(SourceFileManager.forModule(module, TestScope.UNIT))).isNotEmpty()
		assertThat(junitClasses(SourceFileManager.forModule(module, TestScope.ANDROID))).isEmpty()
		assertThat(junitClasses(SourceFileManager.forModule(module))).isEmpty()
	}

	@Test
	fun `a test source gets its package in the binary name`() {
		val unit = SourceFileManager.forModule(module, TestScope.UNIT)

		val binaryName = unit.inferBinaryName(StandardLocation.SOURCE_PATH, sourceFor(unit, "MainTest"))

		assertThat(binaryName).isEqualTo("com.app.MainTest")
	}

	@Test
	fun `a test file belongs to its scope`() {
		assertThat(module.testScopeOf(File(unitDir, "com/app/MainTest.java"))).isEqualTo(TestScope.UNIT)
		assertThat(module.testScopeOf(File(deviceDir, "com/app/DeviceHelper.java"))).isEqualTo(TestScope.ANDROID)
		assertThat(module.testScopeOf(File(mainDir, "com/app/Main.java"))).isNull()
	}

	@Test
	fun `a created file is indexed only where it is visible`() {
		val manager = ProjectManagerImpl.getInstance()
		val newTest = source(unitDir, "NewTest")
		val newMain = source(mainDir, "NewMain")

		manager.onFileCreated(FileCreationEvent(newTest))
		manager.onFileCreated(FileCreationEvent(newMain))

		assertThat(indexedIn("NewTest")).containsExactly(TestScope.UNIT)
		assertThat(indexedIn("NewMain")).containsExactly(null, TestScope.UNIT, TestScope.ANDROID)

		manager.onFileDeleted(FileDeletionEvent(newMain))

		assertThat(indexedIn("NewMain")).isEmpty()
	}

	/** The scopes (null for main) whose trie holds `com.app.[name]`. */
	private fun indexedIn(name: String): List<TestScope?> =
		(listOf(null) + TestScope.entries).filter { module.javaSourceClasses(it).findNode("com.app.$name") != null }

	private fun sourceFor(
		manager: SourceFileManager,
		name: String,
	): JavaFileObject? = manager.getJavaFileForInput(StandardLocation.SOURCE_PATH, "com.app.$name", JavaFileObject.Kind.SOURCE)

	private fun junitClasses(manager: SourceFileManager) =
		manager.list(StandardLocation.CLASS_PATH, "org.junit", setOf(JavaFileObject.Kind.CLASS), false).toList()

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

	private fun androidModule(): AndroidModule {
		val junitKey = "junit"
		val unitGraph =
			AndroidModels.DependencyGraph
				.newBuilder()
				.addKey(junitKey)
				.addNode(AndroidModels.GraphNode.newBuilder().setKeyId(0))
				.addRoot(0)
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
						.setMainSourceSet(
							AndroidModels.SourceSetContainer
								.newBuilder()
								.setSourceProvider(provider(mainDir))
								.setUnitTestSourceProvider(provider(unitDir))
								.setAndroidTestSourceProvider(provider(deviceDir)),
						).setVariantDependencies(
							AndroidModels.VariantDependencies
								.newBuilder()
								.setName("debug")
								.setMainArtifact(AndroidModels.ArtifactDependencies.getDefaultInstance())
								.setUnitTestArtifact(AndroidModels.ArtifactDependencies.newBuilder().setCompileGraph(unitGraph))
								.setAndroidTestArtifact(AndroidModels.ArtifactDependencies.getDefaultInstance())
								.putLibraries(
									junitKey,
									AndroidModels.Library
										.newBuilder()
										.setKey(junitKey)
										.setType(AndroidModels.LibraryType.ExternalJavaLibrary)
										.setArtifactPath(junit.path)
										.build(),
								),
						),
				).build(),
		)
	}

	private fun provider(dir: File) = AndroidModels.SourceProvider.newBuilder().addJavaDirs(dir.path)
}
