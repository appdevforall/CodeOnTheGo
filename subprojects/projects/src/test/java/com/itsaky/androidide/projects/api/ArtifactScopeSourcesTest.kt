package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.models.ArtifactScope
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
class ArtifactScopeSourcesTest {
	private val mainJava = File("/p/app/src/main/java")
	private val unitJava = File("/p/app/src/test/java")
	private val unitKotlin = File("/p/app/src/test/kotlin")
	private val deviceJava = File("/p/app/src/androidTest/java")
	private val unitGenerated = File("/p/app/build/generated/ksp/debugUnitTest/java")
	private val screenshotJava = File("/p/app/src/screenshotTest/java")
	private val fixturesJava = File("/p/app/src/testFixtures/java")
	private val libMainJava = File("/p/lib/src/main/java")
	private val libFixturesJava = File("/p/lib/src/testFixtures/java")

	@After
	fun tearDown() {
		runCatching { ProjectManagerImpl.getInstance().workspace = null }
		ProjectManagerImpl.getInstance().androidBuildVariants = emptyMap()
	}

	@Test
	fun `each test scope has only its own source directories`() {
		val app = app(withTests = true)
		install(app)

		assertThat(app.getSourceDirectories(ArtifactScope.UNIT_TEST)).containsExactly(unitJava, unitKotlin)
		assertThat(app.getSourceDirectories(ArtifactScope.ANDROID_TEST)).containsExactly(deviceJava)
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

		assertThat(app.getCompileClasspaths(ArtifactScope.UNIT_TEST, true))
			.containsExactly(File("/jars/main.jar"), File("/jars/junit.jar"))
		assertThat(app.getCompileClasspaths(ArtifactScope.ANDROID_TEST, true))
			.containsExactly(File("/jars/main.jar"), File("/jars/espresso.jar"))
		assertThat(app.getCompileClasspaths(true)).containsExactly(File("/jars/main.jar"))
	}

	@Test
	fun `a project dependency of a test scope contributes its classpath`() {
		val app = app(withTests = true, unitTestProject = ":lib")
		val lib = lib()
		install(app, lib)

		assertThat(app.getCompileClasspaths(ArtifactScope.UNIT_TEST, true)).contains(File("/jars/lib.jar"))
		assertThat(app.getCompileClasspaths(ArtifactScope.ANDROID_TEST, true)).doesNotContain(File("/jars/lib.jar"))
	}

	@Test
	fun `a test-only project dependency is a module the test scope compiles against`() {
		val app = app(withTests = true, unitTestProject = ":lib")
		install(app, lib())

		assertThat(app.getCompileModuleProjects(ArtifactScope.UNIT_TEST).map { it.path }).containsExactly(":lib")
		assertThat(app.getCompileModuleProjects(ArtifactScope.ANDROID_TEST)).isEmpty()
		assertThat(app.getCompileModuleProjects()).isEmpty()
	}

	@Test
	fun `a test fixture that depends back on the module does not list the module`() {
		val app = app(withTests = true, unitTestProject = ":lib")
		install(app, lib(dependsOn = ":app"))

		assertThat(app.getCompileModuleProjects(ArtifactScope.UNIT_TEST).map { it.path }).containsExactly(":lib")
	}

	@Test
	fun `the selected variant's generated test sources are test source directories`() {
		val app = app(withTests = true)
		install(app)
		ProjectManagerImpl.getInstance().androidBuildVariants =
			mapOf(":app" to BuildVariantInfo(":app", listOf("debug"), "debug"))

		assertThat(app.getSourceDirectories(ArtifactScope.UNIT_TEST)).contains(unitGenerated)
		assertThat(app.getSourceDirectories(ArtifactScope.ANDROID_TEST)).doesNotContain(unitGenerated)
		assertThat(app.getSourceDirectories()).doesNotContain(unitGenerated)
	}

	@Test
	fun `a module without test source sets has empty test scopes`() {
		val app = app(withTests = false)
		install(app)

		for (scope in listOf(ArtifactScope.UNIT_TEST, ArtifactScope.ANDROID_TEST)) {
			assertThat(app.getSourceDirectories(scope)).isEmpty()
			assertThat(app.getCompileClasspaths(scope, true)).isEmpty()
		}
	}

	@Test
	fun `req 2 - screenshot tests are their own scope`() {
		val app = app(withTests = true, extraScopes = mapOf(ArtifactScope.SCREENSHOT_TEST to screenshotJava))
		install(app)

		assertThat(app.getArtifactScopes()).contains(ArtifactScope.SCREENSHOT_TEST)
		assertThat(app.getSourceDirectories(ArtifactScope.SCREENSHOT_TEST)).containsExactly(screenshotJava)
		assertThat(app.getSourceDirectories(ArtifactScope.UNIT_TEST)).doesNotContain(screenshotJava)
	}

	@Test
	fun `req 3 - a component AGP does not report is not a scope`() {
		val app = app(withTests = false)
		install(app)

		assertThat(app.getArtifactScopes()).containsExactly(ArtifactScope.Main)
	}

	@Test
	fun `req 5 - test fixtures sources are visible to unit tests but not to main`() {
		val app = app(withTests = true, extraScopes = mapOf(ArtifactScope.TestFixtures to fixturesJava))
		install(app)

		assertThat(app.getCompileSourceDirectories(ArtifactScope.UNIT_TEST)).contains(fixturesJava)
		assertThat(app.getCompileSourceDirectories(ArtifactScope.Main)).doesNotContain(fixturesJava)
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

		assertThat(module.getSourceDirectories(ArtifactScope.UNIT_TEST)).containsExactly(javaTest)
		assertThat(module.getSourceDirectories(ArtifactScope.ANDROID_TEST)).isEmpty()
		assertThat(module.getSourceDirectories()).doesNotContain(javaTest)
	}

	@Test
	fun `req 1 - main scope includes the selected variant's own source sets`() {
		val app = variantApp()
		install(app)
		select("myStupidVariant")

		assertThat(app.getSourceDirectories())
			.containsAtLeast(File("/p/app/src/myStupidVariant/java"), File("/p/app/src/myStupidVariant/kotlin"), mainJava)
		assertThat(app.getSourceDirectories()).doesNotContain(File("/p/app/src/release/java"))
	}

	@Test
	fun `req 2 - the unit test scope includes the selected variant's test source sets`() {
		val app = variantApp()
		install(app)
		select("myStupidVariant")

		assertThat(app.getSourceDirectories(ArtifactScope.UNIT_TEST))
			.containsAtLeast(unitJava, File("/p/app/src/testMyStupidVariant/java"))
		assertThat(app.getSourceDirectories()).doesNotContain(File("/p/app/src/testMyStupidVariant/java"))
	}

	@Test
	fun `req 6 - switching the selected variant moves main's roots`() {
		val app = variantApp()
		install(app)

		select("myStupidVariant")
		val before = app.getSourceDirectories()
		select("release")

		assertThat(app.getSourceDirectories()).contains(File("/p/app/src/release/java"))
		assertThat(app.getSourceDirectories()).isNotEqualTo(before)
	}

	@Test
	fun `req 7 - a file in another variant's source set has no scope`() {
		val app = variantApp()
		install(app)
		select("myStupidVariant")

		assertThat(app.artifactScopeOf(File("/p/app/src/release/java/a/Only.java"))).isNull()
		assertThat(app.artifactScopeOf(File("/p/app/src/myStupidVariant/java/a/Mine.java"))).isEqualTo(ArtifactScope.Main)
	}

	@Test
	fun `req 7 - a file in no source set AGP knows keeps main's analysis`() {
		val app = variantApp()
		install(app)
		select("myStupidVariant")

		assertThat(app.artifactScopeOf(File("/p/app/src/docs/Foo.kt"))).isEqualTo(ArtifactScope.Main)
		assertThat(app.artifactScopeOf(File("/p/app/src/main/resources/actions/Fixture.java"))).isEqualTo(ArtifactScope.Main)
	}

	@Test
	fun `req 9 - an unresolved variant still yields main from src main`() {
		val app = variantApp()
		install(app)
		ProjectManagerImpl.getInstance().androidBuildVariants = emptyMap()

		assertThat(app.getSourceDirectories()).containsExactly(mainJava)
		assertThat(app.artifactScopeOf(File(mainJava, "a/Main.java"))).isEqualTo(ArtifactScope.Main)
	}

	@Test
	fun `req 10 - unit tests see another module's fixture sources and its main`() {
		val app = appUsingFixturesOf(":lib")
		install(app, lib(withFixtures = true))

		assertThat(app.getCompileSourceDirectories(ArtifactScope.UNIT_TEST)).containsAtLeast(libFixturesJava, libMainJava)
		assertThat(app.getCompileSourceDirectories(ArtifactScope.Main)).doesNotContain(libFixturesJava)
	}

	/** An app whose unit tests depend on the test fixtures of the module at [path]. */
	private fun appUsingFixturesOf(path: String): AndroidModule {
		val base = app(withTests = true, unitTestProject = path).delegate
		val deps =
			base.androidProject.variantDependencies
				.toBuilder()
				.putLibraries(path, projectLibrary(path, testFixtures = true))
		return AndroidModule(base.toBuilder().setAndroidProject(base.androidProject.toBuilder().setVariantDependencies(deps)).build())
	}

	@Test
	fun `a new test fixtures file is indexed for every scope that sees fixtures`() {
		val app = app(withTests = true, extraScopes = mapOf(ArtifactScope.TestFixtures to fixturesJava))
		install(app)

		val tries = app.javaSourceClassesFor(File(fixturesJava, "a/Fixture.java")).map { it.first }

		assertThat(tries).containsExactly(
			app.javaSourceClasses(ArtifactScope.TestFixtures),
			app.javaSourceClasses(ArtifactScope.UNIT_TEST),
			app.javaSourceClasses(ArtifactScope.ANDROID_TEST),
		)
	}

	private fun select(variant: String) {
		ProjectManagerImpl.getInstance().androidBuildVariants =
			mapOf(":app" to BuildVariantInfo(":app", listOf("myStupidVariant", "release"), variant))
	}

	/**
	 * An app with unit tests and two variants, each layering its own `src/<variant>` and
	 * `src/test<Variant>`; every one of those dirs is a source set dir AGP knows for the module.
	 */
	private fun variantApp(): AndroidModule {
		val base = app(withTests = true)
		val knownDirs =
			listOf(mainJava, unitJava, unitKotlin, deviceJava) +
				listOf("myStupidVariant", "release").flatMap { variant ->
					val capitalized = variant.replaceFirstChar(Char::uppercaseChar)
					listOf(File("/p/app/src/$variant/java"), File("/p/app/src/$variant/kotlin"), File("/p/app/src/test$capitalized/java"))
				}
		val project =
			base.delegate.androidProject
				.toBuilder()
				.addAllSourceSetDirPaths(knownDirs.map { it.path })
				.clearVariant()
				.addVariant(layeredVariant("myStupidVariant"))
				.addVariant(layeredVariant("release"))
		return AndroidModule(
			base.delegate
				.toBuilder()
				.setAndroidProject(project)
				.build(),
		)
	}

	private fun layeredVariant(name: String): AndroidModels.AndroidVariant {
		val capitalized = name.replaceFirstChar(Char::uppercaseChar)

		fun layer(dir: String) =
			AndroidModels.VariantArtifactSources
				.newBuilder()
				.addVariantSourceProviders(provider(File(dir)))
				.build()
		return AndroidModels.AndroidVariant
			.newBuilder()
			.setName(name)
			.setMainArtifact(AndroidModels.AndroidArtifact.newBuilder().setName("main"))
			.putArtifactSources(
				ArtifactScope.Main.key,
				AndroidModels.VariantArtifactSources
					.newBuilder()
					.addVariantSourceProviders(provider(File("/p/app/src/$name/java"), File("/p/app/src/$name/kotlin")))
					.build(),
			).putArtifactSources(ArtifactScope.UNIT_TEST.key, layer("/p/app/src/test$capitalized/java"))
			.build()
	}

	/** An app module; [extraScopes] adds scopes with one source dir and main's dependencies. */
	private fun app(
		withTests: Boolean,
		unitTestProject: String? = null,
		extraScopes: Map<ArtifactScope, File> = emptyMap(),
	): AndroidModule {
		val sourceSet = AndroidModels.SourceSetContainer.newBuilder().putSourceProviders(ArtifactScope.Main.key, provider(mainJava))
		val deps =
			AndroidModels.VariantDependencies
				.newBuilder()
				.setName("debug")
				.putArtifacts(ArtifactScope.Main.key, artifact("main"))
				.putLibraries("main", jar("main"))
		if (withTests) {
			sourceSet
				.putSourceProviders(ArtifactScope.UNIT_TEST.key, provider(unitJava, unitKotlin))
				.putSourceProviders(ArtifactScope.ANDROID_TEST.key, provider(deviceJava))
			deps
				.putArtifacts(ArtifactScope.UNIT_TEST.key, artifact(*listOfNotNull("main", "junit", unitTestProject).toTypedArray()))
				.putArtifacts(ArtifactScope.ANDROID_TEST.key, artifact("main", "espresso"))
				.putLibraries("junit", jar("junit"))
				.putLibraries("espresso", jar("espresso"))
			unitTestProject?.let { deps.putLibraries(it, projectLibrary(it)) }
		}
		extraScopes.forEach { (scope, dir) ->
			sourceSet.putSourceProviders(scope.key, provider(dir))
			deps.putArtifacts(scope.key, artifact("main"))
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
								.putArtifactSources(
									ArtifactScope.UNIT_TEST.key,
									AndroidModels.VariantArtifactSources
										.newBuilder()
										.addGeneratedSourceFolderPaths(unitGenerated.path)
										.build(),
								),
						),
				).build(),
		)
	}

	/** A library module; [withFixtures] gives it `src/main/java` and `src/testFixtures/java`. */
	private fun lib(
		dependsOn: String? = null,
		withFixtures: Boolean = false,
	): AndroidModule {
		val deps =
			AndroidModels.VariantDependencies
				.newBuilder()
				.setName("debug")
				.putArtifacts(ArtifactScope.Main.key, artifact(*listOfNotNull("lib", dependsOn).toTypedArray()))
				.putLibraries("lib", jar("lib"))
		dependsOn?.let { deps.putLibraries(it, projectLibrary(it)) }
		val project =
			AndroidModels.AndroidProject
				.newBuilder()
				.setProjectType(AndroidModels.ProjectType.LibraryProject)
		if (withFixtures) {
			deps.putArtifacts(ArtifactScope.TestFixtures.key, artifact("lib"))
			project.setMainSourceSet(
				AndroidModels.SourceSetContainer
					.newBuilder()
					.putSourceProviders(ArtifactScope.Main.key, provider(libMainJava))
					.putSourceProviders(ArtifactScope.TestFixtures.key, provider(libFixturesJava)),
			)
		}
		return AndroidModule(gradleProject(":lib").setAndroidProject(project.setVariantDependencies(deps)).build())
	}

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

	private fun projectLibrary(
		path: String,
		testFixtures: Boolean = false,
	): AndroidModels.Library =
		AndroidModels.Library
			.newBuilder()
			.setKey(path)
			.setType(AndroidModels.LibraryType.Project)
			.setProjectInfo(
				AndroidModels.ProjectInfo
					.newBuilder()
					.setProjectPath(path)
					.setIsTestFixtures(testFixtures),
			).build()

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
