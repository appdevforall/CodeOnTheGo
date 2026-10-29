package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AndroidModuleIntermediateClasspathsTest {
	private lateinit var projectDir: File
	private lateinit var buildDir: File

	@Before
	fun setUp() {
		projectDir =
			File.createTempFile("intermediate-classpaths-", "").apply {
				delete()
				mkdirs()
			}
		buildDir = File(projectDir, "build")
	}

	@After
	fun tearDown() {
		projectDir.deleteRecursively()
	}

	private fun module(): AndroidModule {
		val androidProject =
			AndroidModels.AndroidProject
				.newBuilder()
				.setProjectType(AndroidModels.ProjectType.ApplicationProject)
				.build()

		val gradleProject =
			GradleModels.GradleProject
				.newBuilder()
				.setName("app")
				.setPath(":app")
				.setProjectDirPath(projectDir.absolutePath)
				.setBuildDirPath(buildDir.absolutePath)
				.setBuildScriptPath(File(projectDir, "build.gradle.kts").absolutePath)
				.setAndroidProject(androidProject)
				.build()

		return AndroidModule(gradleProject)
	}

	private fun dir(path: String) = File(buildDir, path).apply { mkdirs() }

	private fun file(path: String) =
		File(buildDir, path).apply {
			parentFile.mkdirs()
			writeText("")
		}

	@Test
	fun includesAgp9BuiltInKotlinClassesAndRJar() {
		val kotlinClasses = dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
		val rJar = file("intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar")

		assertThat(module().getIntermediateClasspaths()).containsExactly(kotlinClasses, rJar)
	}

	@Test
	fun ignoresPackageDirectoriesNamedClassesInsideKotlinOutput() {
		val kotlinClasses = dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
		dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/example/classes")

		assertThat(module().getIntermediateClasspaths()).containsExactly(kotlinClasses)
	}

	@Test
	fun ignoresPackageDirectoriesNamedClassesInsideJavacOutput() {
		val javaClasses = dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
		dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes/com/example/classes")

		assertThat(module().getIntermediateClasspaths()).containsExactly(javaClasses)
	}

	@Test
	fun stillIncludesAgp8Layout() {
		val kotlinClasses = dir("tmp/kotlin-classes/debug")
		val javaClasses = dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
		val rJar = file("intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar")

		assertThat(module().getIntermediateClasspaths()).containsExactly(kotlinClasses, javaClasses, rJar)
	}
}
