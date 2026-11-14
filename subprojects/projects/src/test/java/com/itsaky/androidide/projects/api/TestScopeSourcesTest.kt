package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.tooling.api.models.BuildVariantInfo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests and android tests are separate scopes (ADFA-7115): each sees main plus its own sources
 * and dependencies, never the other's, and main never sees either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TestScopeSourcesTest {
	private val mainJava = File("/p/app/src/main/java")
	private val unitJava = File("/p/app/src/test/java")
	private val unitKotlin = File("/p/app/src/test/kotlin")
	private val deviceJava = File("/p/app/src/androidTest/java")
	private val unitGenerated = File("/p/app/build/generated/ksp/debugUnitTest/java")

	@After
	fun tearDown() {
		runCatching { ProjectManagerImpl.getInstance().workspace = null }
		ProjectManagerImpl.getInstance().androidBuildVariants = emptyMap()
	}

	@Test
	fun `each test scope has only its own source directories`() {
		val app = app(withTests = true)
		install(app)

		assertThat(app.getTestSourceDirectories(TestScope.UNIT)).containsExactly(unitJava, unitKotlin)
		assertThat(app.getTestSourceDirectories(TestScope.ANDROID)).containsExactly(deviceJava)
	}

	@Test
	fun `main source directories leave out test sources`() {
		val app = app(withTests = true)
		install(app)

		assertThat(app.getSourceDirectories()).containsExactly(mainJava)
		assertThat(app.getCompileSourceDirectories()).containsExactly(mainJava)
	}

	@Test
	fun `each test scope compiles against main plus its own dependencies`() {
		val app = app(withTests = true)
		install(app)

		assertThat(app.getTestCompileClasspaths(TestScope.UNIT, true))
			.containsExactly(File("/jars/main.jar"), File("/jars/junit.jar"))
		assertThat(app.getTestCompileClasspaths(TestScope.ANDROID, true))
			.containsExactly(File("/jars/main.jar"), File("/jars/espresso.jar"))
		assertThat(app.getCompileClasspaths(true)).containsExactly(File("/jars/main.jar"))
	}

	@Test
	fun `a project dependency of a test scope contributes its classpath`() {
		val app = app(withTests = true, unitTestProject = ":lib")
		val lib = lib()
		install(app, lib)

		assertThat(app.getTestCompileClasspaths(TestScope.UNIT, true)).contains(File("/jars/lib.jar"))
		assertThat(app.getTestCompileClasspaths(TestScope.ANDROID, true)).doesNotContain(File("/jars/lib.jar"))
	}

	@Test
	fun `a test-only project dependency is a module the test scope compiles against`() {
		val app = app(withTests = true, unitTestProject = ":lib")
		install(app, lib())

		assertThat(app.getTestCompileModuleProjects(TestScope.UNIT).map { it.path }).containsExactly(":lib")
		assertThat(app.getTestCompileModuleProjects(TestScope.ANDROID)).isEmpty()
		assertThat(app.getCompileModuleProjects()).isEmpty()
	}

	@Test
	fun `the selected variant's generated test sources are test source directories`() {
		val app = app(withTests = true)
		install(app)
		ProjectManagerImpl.getInstance().androidBuildVariants =
			mapOf(":app" to BuildVariantInfo(":app", listOf("debug"), "debug"))

		assertThat(app.getTestSourceDirectories(TestScope.UNIT)).contains(unitGenerated)
		assertThat(app.getTestSourceDirectories(TestScope.ANDROID)).doesNotContain(unitGenerated)
		assertThat(app.getSourceDirectories()).doesNotContain(unitGenerated)
	}

	@Test
	fun `a module without test source sets has empty test scopes`() {
		val app = app(withTests = false)
		install(app)

		for (scope in TestScope.entries) {
			assertThat(app.getTestSourceDirectories(scope)).isEmpty()
			assertThat(app.getTestCompileClasspaths(scope, true)).isEmpty()
		}
	}

	@Test
	fun `a java module's test directories are its unit test scope`() {
		val javaTest = File("/p/jvm/src/test/java")
		val module =
			JavaModule(
				gradleProject(":jvm")
					.setJavaProject(
						JavaModels.JavaProject.newBuilder().addContentRoot(
							JavaModels.JavaContentRoot
								.newBuilder()
								.addSourceDirectory(JavaModels.JavaSourceDirectory.newBuilder().setDirectoryPath("/p/jvm/src/main/java"))
								.addTestDirectory(JavaModels.JavaSourceDirectory.newBuilder().setDirectoryPath(javaTest.path)),
						),
					).build(),
			)
		install(module)

		assertThat(module.getTestSourceDirectories(TestScope.UNIT)).containsExactly(javaTest)
		assertThat(module.getTestSourceDirectories(TestScope.ANDROID)).isEmpty()
		assertThat(module.getSourceDirectories()).doesNotContain(javaTest)
	}

	private fun app(
		withTests: Boolean,
		unitTestProject: String? = null,
	): AndroidModule {
		val sourceSet = AndroidModels.SourceSetContainer.newBuilder().setSourceProvider(provider(mainJava))
		val deps =
			AndroidModels.VariantDependencies
				.newBuilder()
				.setName("debug")
				.setMainArtifact(artifact("main"))
				.putLibraries("main", jar("main"))
		if (withTests) {
			sourceSet
				.setUnitTestSourceProvider(provider(unitJava, unitKotlin))
				.setAndroidTestSourceProvider(provider(deviceJava))
			deps
				.setUnitTestArtifact(artifact(*listOfNotNull("main", "junit", unitTestProject).toTypedArray()))
				.setAndroidTestArtifact(artifact("main", "espresso"))
				.putLibraries("junit", jar("junit"))
				.putLibraries("espresso", jar("espresso"))
			unitTestProject?.let { deps.putLibraries(it, projectLibrary(it)) }
		}
		return AndroidModule(
			gradleProject(":app")
				.setAndroidProject(
					AndroidModels.AndroidProject
						.newBuilder()
						.setProjectType(AndroidModels.ProjectType.ApplicationProject)
						.setMainSourceSet(sourceSet)
						.setVariantDependencies(deps)
						.addVariant(
							AndroidModels.AndroidVariant
								.newBuilder()
								.setName("debug")
								.setMainArtifact(AndroidModels.AndroidArtifact.newBuilder().setName("main"))
								.addUnitTestGeneratedSourceFolderPaths(unitGenerated.path),
						),
				).build(),
		)
	}

	private fun lib(): AndroidModule =
		AndroidModule(
			gradleProject(":lib")
				.setAndroidProject(
					AndroidModels.AndroidProject
						.newBuilder()
						.setProjectType(AndroidModels.ProjectType.LibraryProject)
						.setVariantDependencies(
							AndroidModels.VariantDependencies
								.newBuilder()
								.setName("debug")
								.setMainArtifact(artifact("lib"))
								.putLibraries("lib", jar("lib")),
						),
				).build(),
		)

	private fun provider(vararg dirs: File): AndroidModels.SourceProvider {
		val builder = AndroidModels.SourceProvider.newBuilder()
		dirs.forEach { if (it.path.endsWith("kotlin")) builder.addKotlinDirs(it.path) else builder.addJavaDirs(it.path) }
		return builder.build()
	}

	/** An artifact whose compile graph has one root node per key. */
	private fun artifact(vararg keys: String): AndroidModels.ArtifactDependencies {
		val graph = AndroidModels.DependencyGraph.newBuilder()
		keys.forEachIndexed { index, key ->
			graph
				.addKey(key)
				.addNode(AndroidModels.GraphNode.newBuilder().setKeyId(index))
				.addRoot(index)
		}
		return AndroidModels.ArtifactDependencies
			.newBuilder()
			.setCompileGraph(graph)
			.build()
	}

	private fun jar(key: String): AndroidModels.Library =
		AndroidModels.Library
			.newBuilder()
			.setKey(key)
			.setType(AndroidModels.LibraryType.ExternalJavaLibrary)
			.setArtifactPath("/jars/$key.jar")
			.build()

	private fun projectLibrary(path: String): AndroidModels.Library =
		AndroidModels.Library
			.newBuilder()
			.setKey(path)
			.setType(AndroidModels.LibraryType.Project)
			.setProjectInfo(AndroidModels.ProjectInfo.newBuilder().setProjectPath(path))
			.build()

	private fun gradleProject(path: String): GradleModels.GradleProject.Builder {
		val dir = "/p${path.replace(':', '/')}"
		return GradleModels.GradleProject
			.newBuilder()
			.setName(path.trimStart(':'))
			.setPath(path)
			.setProjectDirPath(dir)
			.setBuildDirPath("$dir/build")
			.setBuildScriptPath("$dir/build.gradle")
	}

	private fun install(vararg modules: ModuleProject) {
		val root =
			GradleProject(
				GradleModels.GradleProject
					.newBuilder()
					.setName("root")
					.setPath(":")
					.build(),
			)
		ProjectManagerImpl.getInstance().workspace =
			Workspace(rootProject = root, subProjects = modules.toList(), syncIssues = emptyList())
	}
}
