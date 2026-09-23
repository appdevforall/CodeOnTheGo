package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

@RunWith(JUnit4::class)
class JavaModuleClassesJarTest {
	@get:Rule
	val temp = TemporaryFolder()

	private fun javaModule(buildDir: File): JavaModule =
		JavaModule(
			GradleModels.GradleProject
				.newBuilder()
				.setName("lib")
				.setPath(":lib")
				.setProjectDirPath(buildDir.parentFile.absolutePath)
				.setBuildDirPath(buildDir.absolutePath)
				.setBuildScriptPath(File(buildDir.parentFile, "build.gradle").absolutePath)
				.setJavaProject(JavaModels.JavaProject.getDefaultInstance())
				.build(),
		)

	@Test
	fun `a libs directory without a matching jar resolves to the missing-jar sentinel`() {
		val buildDir = temp.newFolder("lib", "build")
		File(buildDir, "libs").mkdirs()
		File(buildDir, "libs/other.jar").writeBytes(ByteArray(1))

		val jar = javaModule(buildDir).getClassesJar()

		assertThat(jar.exists()).isFalse()
	}

	@Test
	fun `a jar built after the first lookup is found by the next one`() {
		val buildDir = temp.newFolder("lib", "build")
		val module = javaModule(buildDir)
		module.getClassesJar()

		val built = File(buildDir, "libs/lib-1.0.jar").apply { parentFile.mkdirs() }
		built.writeBytes(ByteArray(1))

		assertThat(module.getClassesJar()).isEqualTo(built)
	}
}
