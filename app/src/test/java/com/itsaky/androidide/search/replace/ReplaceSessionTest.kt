package com.itsaky.androidide.search.replace

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.ProjectSearchOptions
import com.itsaky.androidide.utils.RecursiveFileSearcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReplaceSessionTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun session(): ReplaceSession {
		File(tmp.root, "A.java").writeText("needle needle")
		File(tmp.root, "B.java").writeText("needle")
		val results = RecursiveFileSearcher.search("needle", emptyList(), listOf(tmp.root), ProjectSearchOptions.DEFAULT)
		return ReplaceSession("needle", "x", ProjectSearchOptions.DEFAULT, results)
	}

	@Test
	fun everythingStartsIncluded() {
		val s = session()
		assertThat(s.includedCount).isEqualTo(3)
		assertThat(s.includedFileCount).isEqualTo(2)
	}

	@Test
	fun samePositionInAnotherFileIsNotToggled() {
		val s = session()
		val a = s.results.keys.first { it.name == "A.java" }
		val b = s.results.keys.first { it.name == "B.java" }
		val toggled = s.toggleMatch(s.results.getValue(a).first())
		assertThat(toggled.isIncluded(s.results.getValue(b).single())).isTrue()
		assertThat(toggled.fileState(a)).isEqualTo(FileCheckState.SOME)
	}

	@Test
	fun toggleFileFlipsAllItsMatches() {
		val s = session()
		val a = s.results.keys.first { it.name == "A.java" }
		val off = s.toggleFile(a)
		assertThat(off.fileState(a)).isEqualTo(FileCheckState.NONE)
		assertThat(off.includedEdits().map { it.file.name }).containsExactly("B.java")
		assertThat(off.toggleFile(a).fileState(a)).isEqualTo(FileCheckState.ALL)
	}
}
