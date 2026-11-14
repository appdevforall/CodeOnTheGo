package com.itsaky.androidide.lsp.kotlin.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtLibraryModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtSourceModule
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.fixtures.TestSources
import com.itsaky.androidide.lsp.kotlin.fixtures.gradleProject
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.JavaModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import org.junit.After
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.pathString

class CollectKtModulesTest : KtLspTest() {
	@After
	fun clearWorkspace() {
		ProjectManagerImpl.getInstance().workspace = null
	}

	@Test
	fun `a module dependency cycle builds each source module once`() {
		val sourceModules =
			collect(
				listOf(
					AndroidModule(gradleProject(":liba", projectDeps = listOf(":libb"))),
					AndroidModule(gradleProject(":libb", projectDeps = listOf(":liba"))),
				),
			)

		val libA = sourceModules.single { it.id == ":liba" }
		val libB = sourceModules.single { it.id == ":libb" }
		// Before ADFA-5320 this recursed :liba -> :libb -> :liba until the stack overflowed.
		assertThat(libA.directRegularDependencies.filterIsInstance<KtSourceModule>()).containsExactly(libB)
	}

	@Test
	fun `modules sharing android jar share one library module`() {
		val androidJar = tempJar("android.jar")
		val modulePaths = listOf(":a", ":b", ":c")

		val sourceModules = collect(modulePaths.map { AndroidModule(gradleProject(it, bootClassPath = androidJar)) })

		val androidJarDeps = sourceModules.map { it.libraryDeps(androidJar) }
		assertThat(sourceModules).hasSize(modulePaths.size)
		// Before ADFA-6381 each source module depended on one fresh copy per Android module (3 x 3 here).
		androidJarDeps.forEach { assertThat(it).hasSize(1) }
		androidJarDeps.flatten().forEach { assertThat(it).isSameInstanceAs(androidJarDeps.first().first()) }
	}

	@Test
	fun `modules sharing a compile classpath jar share one library module`() {
		val libJar = tempJar("lib.jar")

		val sourceModules = collect(listOf(":a", ":b").map { JavaModule(gradleProject(it, compileJars = listOf(libJar))) })

		val libJarDeps = sourceModules.map { it.libraryDeps(libJar) }
		libJarDeps.forEach { assertThat(it).hasSize(1) }
		assertThat(libJarDeps[1].single()).isSameInstanceAs(libJarDeps[0].single())
	}

	@Test
	fun `a jar on both the boot and compile classpath is one dependency`() {
		val androidJar = tempJar("android.jar")

		// Boot classpaths from every Android module go to every source module, so :j sees
		// android.jar twice: once from :a's boot classpath, once from its own compile classpath.
		val sourceModules =
			collect(
				listOf(
					AndroidModule(gradleProject(":a", bootClassPath = androidJar)),
					JavaModule(gradleProject(":j", compileJars = listOf(androidJar))),
				),
			)

		assertThat(sourceModules.single { it.id == ":j" }.libraryDeps(androidJar)).hasSize(1)
	}

	@Test
	fun `each test source set is its own module over main`() {
		val junit = tempJar("junit.jar")
		val espresso = tempJar("espresso.jar")
		val sourceModules =
			collect(
				listOf(
					AndroidModule(
						gradleProject(
							":app",
							unitTests = TestSources(listOf(tempDir("app/src/test/java")), listOf(junit)),
							androidTests = TestSources(listOf(tempDir("app/src/androidTest/java")), listOf(espresso)),
						),
					),
				),
			)

		val main = sourceModules.single { it.id == ":app" }
		val unit = sourceModules.single { it.id == ":app[unitTest]" }
		val device = sourceModules.single { it.id == ":app[androidTest]" }

		assertThat(main.contentRoots).containsNoneIn(unit.contentRoots + device.contentRoots)
		for (test in listOf(unit, device)) {
			assertThat(test.directRegularDependencies).contains(main)
			assertThat(test.directFriendDependencies).containsExactly(main)
		}
		assertThat(main.directFriendDependencies).isEmpty()

		// The scopes are isolated: neither test module, nor main, sees the other's sources or jars.
		assertThat(unit.directRegularDependencies).doesNotContain(device)
		assertThat(device.directRegularDependencies).doesNotContain(unit)
		assertThat(unit.libraryDeps(junit)).hasSize(1)
		assertThat(unit.libraryDeps(espresso)).isEmpty()
		assertThat(device.libraryDeps(espresso)).hasSize(1)
		assertThat(device.libraryDeps(junit)).isEmpty()
		assertThat(main.libraryDeps(junit) + main.libraryDeps(espresso)).isEmpty()
	}

	@Test
	fun `a test module also depends on what main depends on`() {
		val sourceModules =
			collect(
				listOf(
					AndroidModule(gradleProject(":lib", bootClassPath = tempJar("android.jar"))),
					AndroidModule(
						gradleProject(
							":app",
							projectDeps = listOf(":lib"),
							unitTests = TestSources(listOf(tempDir("app/src/test/java"))),
						),
					),
				),
			)

		val lib = sourceModules.single { it.id == ":lib" }
		val unit = sourceModules.single { it.id == ":app[unitTest]" }
		assertThat(unit.directRegularDependencies).contains(lib)
		// lib's internals stay hidden: only the test module's own main module is a friend.
		assertThat(unit.directFriendDependencies.map { it.id }).containsExactly(":app")
	}

	@Test
	fun `a test-only project dependency is a source module of that test scope only`() {
		val sourceModules =
			collect(
				listOf(
					AndroidModule(gradleProject(":lib", bootClassPath = tempJar("android.jar"))),
					AndroidModule(
						gradleProject(
							":app",
							unitTests = TestSources(listOf(tempDir("app/src/test/java")), projects = listOf(":lib")),
							androidTests = TestSources(listOf(tempDir("app/src/androidTest/java"))),
						),
					),
				),
			)

		val lib = sourceModules.single { it.id == ":lib" }
		assertThat(sourceModules.single { it.id == ":app[unitTest]" }.directRegularDependencies).contains(lib)
		assertThat(sourceModules.single { it.id == ":app[androidTest]" }.directRegularDependencies).doesNotContain(lib)
		assertThat(sourceModules.single { it.id == ":app" }.directRegularDependencies).doesNotContain(lib)
	}

	private fun tempDir(name: String): Path =
		lspTestRule.tempDir.root
			.toPath()
			.resolve(name)
			.also { it.toFile().mkdirs() }

	private fun tempJar(name: String): Path =
		lspTestRule.tempDir.root
			.toPath()
			.resolve(name)
			.createFile()

	// Module dependencies resolve through the global workspace, so this one is installed there.
	private fun collect(subProjects: List<ModuleProject>): List<KtSourceModule> {
		val workspace =
			Workspace(
				rootProject = GradleProject(gradleProject(":")),
				subProjects = subProjects,
				syncIssues = emptyList(),
			)
		ProjectManagerImpl.getInstance().workspace = workspace
		return workspace
			.kotlinModuleLayout()
			.toKtModules(env.project, env.applicationEnv)
			.filterIsInstance<KtSourceModule>()
	}

	private fun KtSourceModule.libraryDeps(jar: Path) =
		directRegularDependencies.filterIsInstance<KtLibraryModule>().filter { it.id == jar.pathString }
}
