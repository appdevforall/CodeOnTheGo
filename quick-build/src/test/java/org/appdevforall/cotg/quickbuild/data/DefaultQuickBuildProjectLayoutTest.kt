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
	fun `collects kotlin and java sources under the main source roots`() {
		write("app/src/main/java/com/example/A.java")
		write("app/src/main/kotlin/com/example/B.kt")
		write("app/src/main/res/values/strings.xml", "<resources/>")

		val sources = DefaultQuickBuildProjectLayout(root).allSources().map { it.name }

		assertThat(sources).containsExactly("A.java", "B.kt")
	}

	@Test
	fun `includes generated source roots reported by the setup build`() {
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
}
