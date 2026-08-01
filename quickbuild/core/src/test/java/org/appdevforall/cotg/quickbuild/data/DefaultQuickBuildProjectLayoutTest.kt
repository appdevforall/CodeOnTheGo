package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The source set the daemon compiles, including processor-generated roots. */
class DefaultQuickBuildProjectLayoutTest {
	@TempDir
	lateinit var root: File

	private fun write(
		path: String,
		text: String = "class X",
	): File = File(root, path).apply { parentFile.mkdirs() }.apply { writeText(text) }

	@Test
	fun `stableIdsFile returns the proxy app build's reported file`() {
		val stableIds = write("app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt", "")

		val layout = DefaultQuickBuildProjectLayout(root, stableIdsFile = stableIds)

		assertThat(layout.stableIdsFile()).isEqualTo(stableIds)
	}

	@Test
	fun `stableIdsFile is null when the proxy app build did not report one`() {
		val layout = DefaultQuickBuildProjectLayout(root)

		assertThat(layout.stableIdsFile()).isNull()
	}

	@Test
	fun `libraryResourceFlats returns the proxy app build's reported units`() {
		val mergedRes = write("app/build/intermediates/merged_res/debug/values_values.arsc.flat", "")
		val libraryFile = write("gradle-cache/transformed/com.google.android.material/drawable_x.xml.flat", "")

		val layout = DefaultQuickBuildProjectLayout(root, libraryResourceFlats = listOf(mergedRes, libraryFile))

		assertThat(layout.libraryResourceFlats()).containsExactly(mergedRes, libraryFile).inOrder()
	}

	@Test
	fun `libraryResourceFlats is empty when the proxy app build did not report any`() {
		val layout = DefaultQuickBuildProjectLayout(root)

		assertThat(layout.libraryResourceFlats()).isEmpty()
	}

	@Test
	fun `collects kotlin and java sources under the main source roots`() {
		write("app/src/main/java/com/example/A.java")
		write("app/src/main/kotlin/com/example/B.kt")
		write("app/src/main/res/values/strings.xml", "<resources/>")

		val sources = DefaultQuickBuildProjectLayout(root).allSources().map { it.name }

		assertThat(sources).containsExactly("A.java", "B.kt")
	}

	@Test
	fun `includes generated source roots reported by the proxy app build`() {
		write("app/src/main/java/com/example/A.kt")
		val generated = write("app/build/generated/ksp/v8Debug/kotlin/com/example/ADao_Impl.kt")

		val sources =
			DefaultQuickBuildProjectLayout(
				projectRoot = root,
				extraSourceRoots = listOf(File(root, "app/build/generated/ksp/v8Debug/kotlin")),
			).allSources()

		assertThat(sources.map { it.name }).containsExactly("A.kt", "ADao_Impl.kt")
		assertThat(sources.map { it.absolutePath }).contains(generated.absolutePath)
	}

	@Test
	fun `a generated root that repeats a main root does not duplicate sources`() {
		write("app/src/main/java/com/example/A.kt")

		val sources =
			DefaultQuickBuildProjectLayout(
				projectRoot = root,
				extraSourceRoots = listOf(File(root, "app/src/main/java")),
			).allSources()

		assertThat(sources).hasSize(1)
	}

	@Test
	fun `a missing generated root is ignored`() {
		write("app/src/main/java/com/example/A.kt")

		val sources =
			DefaultQuickBuildProjectLayout(
				projectRoot = root,
				extraSourceRoots = listOf(File(root, "app/build/generated/ksp/v8Debug/kotlin")),
			).allSources()

		assertThat(sources.map { it.name }).containsExactly("A.kt")
	}

	@Test
	fun `generated roots are compiled but never watched`() {
		val layout =
			DefaultQuickBuildProjectLayout(
				projectRoot = root,
				extraSourceRoots = listOf(File(root, "app/build/generated/ksp/v8Debug/kotlin")),
			)

		// Watching build/ would feed the loop its own output.
		assertThat(layout.watchedRoots()).containsExactly(File(root, "app/src"))
	}

	@Test
	fun `watchedRoots spans every module's src so a library edit is seen`() {
		write("app/build.gradle.kts")
		write("feature-login/build.gradle.kts")
		write("core/ui/build.gradle")

		val roots = DefaultQuickBuildProjectLayout(root).watchedRoots()

		assertThat(roots).containsExactly(
			File(root, "app/src"),
			File(root, "feature-login/src"),
			File(root, "core/ui/src"),
		)
	}

	@Test
	fun `watchedFiles includes every module's build script plus root gradle config`() {
		write("app/build.gradle.kts")
		write("feature-login/build.gradle")

		val watched = DefaultQuickBuildProjectLayout(root).watchedFiles()

		assertThat(watched).containsAtLeast(
			File(root, "settings.gradle.kts"),
			File(root, "gradle/libs.versions.toml"),
			File(root, "app/build.gradle.kts"),
			File(root, "feature-login/build.gradle"),
		)
	}

	@Test
	fun `module discovery skips build intermediates and hidden dirs`() {
		write("app/build.gradle.kts")
		// A stray build script under build/ or a hidden dir must NOT become a watched module.
		write("app/build/generated/some-tool/build.gradle")
		write(".gradle/tmp/build.gradle")

		val roots = DefaultQuickBuildProjectLayout(root).watchedRoots()

		assertThat(roots).containsExactly(File(root, "app/src"))
	}

	@Test
	fun `liveReloadScope is only the app module even in a multi-module project`() {
		write("app/build.gradle.kts")
		write("feature-login/build.gradle.kts")

		assertThat(DefaultQuickBuildProjectLayout(root).liveReloadScope())
			.containsExactly(File(root, "app/src"))
	}
}
