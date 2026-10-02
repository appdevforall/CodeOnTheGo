package org.appdevforall.cotg.quickbuild.domain.watch

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WatchFilterTest {
	@TempDir
	lateinit var tempDir: File

	private fun filter(): WatchFilter =
		WatchFilter(
			watchedRoots = listOf(File(tempDir, "app/src")),
			watchedFiles = listOf(File(tempDir, "app/build.gradle.kts")),
		)

	@Test
	fun `kt file under the src root is relevant`() {
		val file = File(tempDir, "app/src/main/kotlin/Foo.kt")

		assertThat(filter().isRelevant(file)).isTrue()
	}

	@Test
	fun `a gradle intermediate is not relevant`() {
		// Gradle's build/ is a module-root child, so under the production layout (roots are
		// <module>/src) it already falls outside every root; it reaches the build-segment test
		// only when a caller watches the module dir itself. Both paths must exclude it.
		val intermediate = File(tempDir, "app/build/generated/Foo.kt")

		assertThat(filter().isRelevant(intermediate)).isFalse()
		assertThat(WatchFilter(watchedRoots = listOf(File(tempDir, "app"))).isRelevant(intermediate)).isFalse()
	}

	@Test
	fun `a code file in a package named build is relevant`() {
		// `build` is a legal Kotlin/Java package name. This filter sits upstream of BOTH the
		// inotify and the poll channel, so a wrong drop here means the save reaches nothing at
		// all - no build, no batch, no warning, and no poll sweep can rescue it.
		val file = File(tempDir, "app/src/main/java/com/example/build/Builders.kt")

		assertThat(filter().isRelevant(file)).isTrue()
		assertThat(WatchFilter(watchedRoots = listOf(File(tempDir, "app"))).isRelevant(file)).isTrue()
	}

	@Test
	fun `file outside all roots is not relevant`() {
		val file = File(tempDir, "other/x.kt")

		assertThat(filter().isRelevant(file)).isFalse()
	}

	@Test
	fun `the watched loose file is relevant`() {
		val file = File(tempDir, "app/build.gradle.kts")

		assertThat(filter().isRelevant(file)).isTrue()
	}

	@Test
	fun `a different loose gradle file not in watchedFiles is not relevant`() {
		val file = File(tempDir, "app/settings.gradle.kts")

		assertThat(filter().isRelevant(file)).isFalse()
	}

	@Test
	fun `temp artifacts under the src root are never relevant`() {
		val names = listOf(".hidden.kt", "Main.kt~", "Main.kt.tmp", "x.swp", "y.bak")

		names.forEach { name ->
			val file = File(tempDir, "app/src/main/kotlin/$name")

			assertThat(filter().isRelevant(file)).isFalse()
		}
	}

	@Test
	fun `patch and merge droppings under the src root are never relevant`() {
		// audit Gap B: a persisted .orig/.rej would otherwise classify UNSUPPORTED and
		// force a spurious full Gradle rebaseline instead of the intended quick path.
		val names = listOf("Main.kt.orig", "Main.kt.rej")

		names.forEach { name ->
			val file = File(tempDir, "app/src/main/kotlin/$name")

			assertThat(filter().isRelevant(file)).isFalse()
		}
	}

	@Test
	fun `JGit checkout dot-prefixed temp files under src are never relevant`() {
		// audit rows 9, 10, 12: JGit's DirCacheCheckout writes a `._<name>`-prefixed temp in
		// the target dir then renames it onto the target. The dot-prefix drops the temp here,
		// so only the final MOVED_TO onto the real path reaches the pipeline.
		val names = listOf("._Main.kt", ".merge_file_aBc12", "._strings.xml")

		names.forEach { name ->
			val file = File(tempDir, "app/src/main/kotlin/$name")

			assertThat(filter().isRelevant(file)).isFalse()
		}
	}

	@Test
	fun `an unrecognized external-tool temp is relevant here and dropped only downstream`() {
		// audit row 14: `sed -i` writes a sibling `sedXXXXXX` temp - no dot-prefix, no known
		// suffix, no extension - so the NAME filter deliberately cannot recognize it and it
		// passes as relevant. It is dropped later at batch-settle (once it has vanished AND
		// has no recognized project-file shape), NOT by widening this name filter. Pinning
		// isRelevant==true here guards against a broad name rule that would wrongly also drop
		// real files (bug11 covers the downstream drop; see QuickBuildSessionManagerTest).
		val sedTemp = File(tempDir, "app/src/main/kotlin/sedAbC123")

		assertThat(filter().isRelevant(sedTemp)).isTrue()
	}
}
