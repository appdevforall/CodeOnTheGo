package com.itsaky.androidide.viewmodel

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.FileMatch
import org.junit.Test
import java.io.File

class FileSearchUiStateTest {
	private val root = File("/projects/MyApp")

	private fun results(
		isGlob: Boolean,
		vararg paths: String,
	) = FileSearchUiState.Results(root, paths.map { FileMatch(it, emptyList()) }, isGlob)

	private fun numberedPaths(count: Int) = Array(count) { "src/File$it.kt" }

	@Test
	fun fuzzyEnterOpensOnlyTheTopMatch() {
		assertThat(results(isGlob = false, "app/Main.kt", "app/MainTest.kt").filesOpenedByEnter)
			.containsExactly(File("/projects/MyApp/app/Main.kt"))
	}

	@Test
	fun fuzzyEnterWithoutMatchesOpensNothing() {
		assertThat(results(isGlob = false).filesOpenedByEnter).isEmpty()
	}

	@Test
	fun globEnterOpensEveryMatch() {
		assertThat(results(isGlob = true, "a.kt", "b/c.kt").filesOpenedByEnter)
			.containsExactly(File("/projects/MyApp/a.kt"), File("/projects/MyApp/b/c.kt"))
			.inOrder()
	}

	@Test
	fun globEnterOpensTwentyMatches() {
		val state = results(isGlob = true, *numberedPaths(20))
		assertThat(state.exceedsOpenLimit).isFalse()
		assertThat(state.filesOpenedByEnter).hasSize(20)
	}

	@Test
	fun globEnterOpensNothingPastTwentyMatches() {
		val state = results(isGlob = true, *numberedPaths(21))
		assertThat(state.exceedsOpenLimit).isTrue()
		assertThat(state.filesOpenedByEnter).isEmpty()
	}

	@Test
	fun fuzzyIsNeverOverTheLimit() {
		val state = results(isGlob = false, *numberedPaths(21))
		assertThat(state.exceedsOpenLimit).isFalse()
		assertThat(state.filesOpenedByEnter).containsExactly(File("/projects/MyApp/src/File0.kt"))
	}

	@Test
	fun fileOfResolvesAgainstTheProjectRoot() {
		val state = results(isGlob = false, "app/src/Main.kt")
		assertThat(state.fileOf(state.matches.single())).isEqualTo(File("/projects/MyApp/app/src/Main.kt"))
	}
}
