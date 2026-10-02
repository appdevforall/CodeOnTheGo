package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

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
		val options =
			ProjectSearchOptions(
				excludedDirNames = ProjectSearchOptions.PROJECT_ROOT_EXCLUDED_DIR_NAMES,
				nameExclusionRoot = tmp.root,
			)
		assertThat(search("needle", options = options).keys).containsExactly(kept)
	}

	@Test
	fun excludedDirNamesApplyAtAnyDepthUnderTheProjectRoot() {
		write("build-logic/build/gen/A.java", "needle")
		val kept = write("build-logic/src/B.java", "needle")
		val options =
			ProjectSearchOptions(
				excludedDirNames = ProjectSearchOptions.PROJECT_ROOT_EXCLUDED_DIR_NAMES,
				nameExclusionRoot = tmp.root,
			)
		assertThat(search("needle", options = options).keys).containsExactly(kept)
	}

	@Test
	fun excludedDirNamesDoNotHideSourcePackagesInModules() {
		val module = File(tmp.root, "app")
		val inBuildPackage = write("app/src/main/java/com/acme/build/Builder.java", "needle")
		write("build/gen/A.java", "needle")
		val options =
			ProjectSearchOptions(
				excludedDirNames = ProjectSearchOptions.PROJECT_ROOT_EXCLUDED_DIR_NAMES,
				nameExclusionRoot = tmp.root,
				excludedDirs = setOf(module),
			)
		val result = search("needle", dirs = listOf(File(module, "src"), tmp.root), options = options)
		assertThat(result.keys).containsExactly(inBuildPackage)
	}

	@Test
	fun moduleBuildScriptsAreSearchedWhenOnlyModuleSourcesAreExcluded() {
		val moduleSrc = File(tmp.root, "app/src")
		val inSrc = write("app/src/main/A.java", "needle")
		val moduleScript = write("app/build.gradle.kts", "needle")
		write("app/build/gen/B.java", "needle")
		val rootScript = write("build.gradle.kts", "needle")
		val options =
			ProjectSearchOptions(
				excludedDirNames = ProjectSearchOptions.PROJECT_ROOT_EXCLUDED_DIR_NAMES,
				nameExclusionRoot = tmp.root,
				excludedDirs = setOf(moduleSrc),
			)
		val result = search("needle", dirs = listOf(moduleSrc, tmp.root), options = options)
		assertThat(result.keys).containsExactly(inSrc, moduleScript, rootScript)
	}

	@Test
	fun symlinksAreNotFollowed() {
		val outside = TemporaryFolder().apply { create() }
		try {
			File(outside.root, "Outside.java").writeText("needle")
			val kept = write("a/A.java", "needle")
			Files.createSymbolicLink(File(tmp.root, "linked").toPath(), outside.root.toPath())
			Files.createSymbolicLink(File(tmp.root, "a/loop").toPath(), tmp.root.toPath())
			Files.createSymbolicLink(File(tmp.root, "a/Linked.java").toPath(), File(outside.root, "Outside.java").toPath())
			assertThat(search("needle").keys).containsExactly(kept)
		} finally {
			outside.delete()
		}
	}

	@Test
	fun previewOffsetPointsAtTheMatchNotAnEarlierOccurrence() {
		write("a/A.java", "foo(foo)")
		val hits = search("foo").values.single()
		assertThat(hits.map { it.line.substring(it.matchOffset, it.matchOffset + 3) }).containsExactly("foo", "foo")
		assertThat(hits[1].matchOffset).isGreaterThan(hits[0].matchOffset)
		assertThat(hits[1].line.substring(hits[1].matchOffset - 1, hits[1].matchOffset)).isEqualTo("(")
	}

	@Test
	fun previewOffsetSurvivesCollapsedWhitespace() {
		write("a/A.java", "val   x =\n\n\t  target")
		val hit = search("target").values.single().single()
		assertThat(hit.line.substring(hit.matchOffset, hit.matchOffset + "target".length)).isEqualTo("target")
	}

	@Test
	fun previewOffsetForAMatchStartingWithWhitespace() {
		write("a/A.java", "a    b")
		val hit = search(" b").values.single().single()
		val matched = hit.match.replace(Regex("\\s+"), " ")
		assertThat(hit.line.substring(hit.matchOffset, hit.matchOffset + matched.length)).isEqualTo(matched)
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
