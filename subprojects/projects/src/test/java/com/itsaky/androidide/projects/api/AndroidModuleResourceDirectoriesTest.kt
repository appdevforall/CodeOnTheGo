package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.tooling.api.models.BuildVariantInfo
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A resource saved under the selected variant's source sets (`src/debug/res`, `src/<flavor>/res`)
 * is an Android resource, so the save regenerates sources like one under `src/main/res` does.
 * Only the selected variant's directories count: the other build type's `res` is not part of the
 * build the user runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AndroidModuleResourceDirectoriesTest {
	// The module is found for a file by path prefix, and only for a file that exists on disk.
	@get:Rule
	val tmp = TemporaryFolder()

	private val projectDir by lazy { File(tmp.root, "app") }
	private val mainRes by lazy { File(projectDir, "src/main/res") }
	private val debugRes by lazy { File(projectDir, "src/debug/res") }
	private val releaseRes by lazy { File(projectDir, "src/release/res") }

	@After
	fun tearDown() {
		val manager = ProjectManagerImpl.getInstance()
		runCatching { manager.workspace = null }
		manager.androidBuildVariants = emptyMap()
	}

	@Test
	fun `a file under the selected variant's res dir is an Android resource`() {
		installApp(selectedVariant = "debug")

		assertThat(ProjectManagerImpl.getInstance().isAndroidResource(existing(File(debugRes, "values/strings.xml"))))
			.isTrue()
	}

	@Test
	fun `a file under the main res dir is still an Android resource`() {
		installApp(selectedVariant = "debug")

		assertThat(ProjectManagerImpl.getInstance().isAndroidResource(existing(File(mainRes, "layout/main.xml"))))
			.isTrue()
	}

	@Test
	fun `a file under an unselected variant's res dir is not an Android resource`() {
		installApp(selectedVariant = "debug")

		assertThat(ProjectManagerImpl.getInstance().isAndroidResource(existing(File(releaseRes, "values/strings.xml"))))
			.isFalse()
	}

	@Test
	fun `the resource directories are main plus the selected variant's`() {
		val module = installApp(selectedVariant = "release")

		assertThat(module.getResourceDirectories()).containsExactly(mainRes, releaseRes)
	}

	private fun existing(file: File): File {
		file.parentFile.mkdirs()
		file.createNewFile()
		return file
	}

	private fun sourceProvider(res: File): AndroidModels.SourceProvider =
		AndroidModels.SourceProvider
			.newBuilder()
			.addResDirs(res.path)
			.build()

	private fun variant(
		name: String,
		res: File,
	): AndroidModels.AndroidVariant =
		AndroidModels.AndroidVariant
			.newBuilder()
			.setName(name)
			.setMainArtifact(AndroidModels.AndroidArtifact.newBuilder().setName("main"))
			.addVariantSourceProviders(sourceProvider(res))
			.build()

	/** Install one application module with `debug` and `release` variants and select [selectedVariant]. */
	private fun installApp(selectedVariant: String): AndroidModule {
		val androidProject =
			AndroidModels.AndroidProject
				.newBuilder()
				.setProjectType(AndroidModels.ProjectType.ApplicationProject)
				.setMainSourceSet(
					AndroidModels.SourceSetContainer.newBuilder().setSourceProvider(sourceProvider(mainRes)),
				).addVariant(variant("debug", debugRes))
				.addVariant(variant("release", releaseRes))
				.build()
		val module =
			AndroidModule(
				GradleModels.GradleProject
					.newBuilder()
					.setName("app")
					.setPath(":app")
					.setProjectDirPath(projectDir.path)
					.setBuildDirPath(File(projectDir, "build").path)
					.setBuildScriptPath(File(projectDir, "build.gradle").path)
					.setAndroidProject(androidProject)
					.build(),
			)
		val root =
			GradleProject(
				GradleModels.GradleProject
					.newBuilder()
					.setName("root")
					.setPath(":")
					.build(),
			)
		val manager = ProjectManagerImpl.getInstance()
		manager.workspace = Workspace(rootProject = root, subProjects = listOf(module), syncIssues = emptyList())
		manager.androidBuildVariants =
			mapOf(":app" to BuildVariantInfo(":app", listOf("debug", "release"), selectedVariant))
		return module
	}
}
