package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecursiveFileSearcherTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun write(
		relative: String,
		text: String,
	): File =
		File(tmp.root, relative).apply {
			parentFile.mkdirs()
			writeText(text)
		}

	private fun search(
		query: String,
		dirs: List<File> = listOf(tmp.root),
		options: ProjectSearchOptions = ProjectSearchOptions.DEFAULT,
	) = RecursiveFileSearcher.search(query, emptyList(), dirs, options)

	@Test
	fun caseInsensitiveByDefault() {
		write("a/A.java", "Server server SERVER")
		assertThat(search("server").values.single()).hasSize(3)
	}

	@Test
	fun matchCaseOnlyMatchesExactCase() {
		write("a/A.java", "Server server SERVER")
		val hits = search("Server", options = ProjectSearchOptions(matchCase = true)).values.single()
		assertThat(hits.map { it.match }).containsExactly("Server")
	}

	@Test
	fun wholeWordSkipsLongerIdentifiers() {
		write("a/A.java", "server3 server33")
		val hits = search("server3", options = ProjectSearchOptions(wholeWord = true)).values.single()
		assertThat(hits).hasSize(1)
		assertThat(hits.single().start.column).isEqualTo(0)
	}

	@Test
	fun bufferOverrideBeatsDisk() {
		val file = write("a/A.java", "old text")
		val options = ProjectSearchOptions(bufferOverrides = mapOf(file.absoluteFile to "new text"))
		assertThat(search("old", options = options)).isEmpty()
		assertThat(search("new", options = options).keys).containsExactly(file)
	}

	@Test
	fun excludedDirNamesAreNotDescended() {
		write("build/gen/A.java", "needle")
		write(".gradle/B.java", "needle")
		val kept = write("gradle/libs.versions.toml", "needle")
		val options = ProjectSearchOptions(excludedDirNames = ProjectSearchOptions.PROJECT_ROOT_EXCLUDED_DIR_NAMES)
		assertThat(search("needle", options = options).keys).containsExactly(kept)
	}

	@Test
	fun excludedDirsAreSkippedAndNoFileIsReturnedTwice() {
		val module = File(tmp.root, "app")
		val inModule = write("app/src/main/A.java", "needle")
		write("app/build.gradle.kts", "needle")
		val root = write("build.gradle.kts", "needle")
		val options = ProjectSearchOptions(excludedDirs = setOf(module))
		val result = search("needle", dirs = listOf(File(module, "src"), tmp.root), options = options)
		assertThat(result.keys).containsExactly(inModule, root)
	}

	@Test
	fun multiLineRangeIsReported() {
		write("a/A.java", "one\ntwo")
		val hit = search("one\ntwo").values.single().single()
		assertThat(hit.start.line).isEqualTo(0)
		assertThat(hit.end.line).isEqualTo(1)
		assertThat(hit.match).isEqualTo("one\ntwo")
	}
}
