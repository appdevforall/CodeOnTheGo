package org.appdevforall.cotg.quickbuild.domain

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
	fun `file under a build dir inside a watched root is not relevant`() {
		val file = File(tempDir, "app/src/main/build/generated/Foo.kt")

		assertThat(filter().isRelevant(file)).isFalse()
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
}
