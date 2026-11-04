package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.regex.PatternSyntaxException

class FileQueryTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun search(
		query: String,
		vararg paths: String,
	) = FileQuery.parse(query).search(paths.toList())

	private fun ranked(
		query: String,
		vararg paths: String,
	) = search(query, *paths).map { it.relativePath }

	@Test
	fun fuzzyMatchesLettersInOrder() {
		assertThat(ranked("mavm", "app/ViewModelMain.kt", "app/MainViewModel.kt"))
			.containsExactly("app/MainViewModel.kt")
	}

	@Test
	fun fuzzyIgnoresCase() {
		assertThat(ranked("MAIN", "res/main.xml")).containsExactly("res/main.xml")
	}

	@Test
	fun fuzzyWithoutMatchReturnsNothing() {
		assertThat(ranked("xyz", "app/MainActivity.kt")).isEmpty()
	}

	@Test
	fun consecutiveRunOutranksScatteredHumps() {
		assertThat(ranked("main", "a/MyAppInit.kt", "z/MainActivity.kt"))
			.containsExactly("z/MainActivity.kt", "a/MyAppInit.kt")
			.inOrder()
	}

	@Test
	fun camelHumpOutranksMidWordLetter() {
		assertThat(ranked("vm", "a/appviewmodel.kt", "z/AppViewModel.kt"))
			.containsExactly("z/AppViewModel.kt", "a/appviewmodel.kt")
			.inOrder()
	}

	@Test
	fun startOfNameOutranksMidWord() {
		assertThat(ranked("act", "a/react.kt", "z/activity_main.xml"))
			.containsExactly("z/activity_main.xml", "a/react.kt")
			.inOrder()
	}

	@Test
	fun shorterGapOutranksLongerGap() {
		assertThat(ranked("ab", "a/axxxb.kt", "z/axbxxxx.kt"))
			.containsExactly("z/axbxxxx.kt", "a/axxxb.kt")
			.inOrder()
	}

	@Test
	fun letterAfterSeparatorOutranksMidWord() {
		assertThat(ranked("act", "a/react.kt", "z/layout_activity.xml"))
			.containsExactly("z/layout_activity.xml", "a/react.kt")
			.inOrder()
	}

	@Test
	fun equalScoresPreferShorterName() {
		assertThat(ranked("main", "a/main_activity.xml", "z/main.xml"))
			.containsExactly("z/main.xml", "a/main_activity.xml")
			.inOrder()
	}

	@Test
	fun fuzzyWithoutSlashIgnoresDirectoryNames() {
		assertThat(ranked("values", "res/values/strings.xml", "res/values.xml"))
			.containsExactly("res/values.xml")
	}

	@Test
	fun fuzzyHighlightsIndexTheRelativePath() {
		assertThat(search("mavm", "src/MainViewModel.kt").single().highlights)
			.containsExactly(4, 5, 8, 12)
			.inOrder()
	}

	@Test
	fun fuzzyWithSlashMatchesTheRelativePath() {
		val match = search("ui/m", "app/data/MainRepo.kt", "app/ui/MainScreen.kt").single()
		assertThat(match.relativePath).isEqualTo("app/ui/MainScreen.kt")
		assertThat(match.highlights).containsExactly(4, 5, 6, 7).inOrder()
	}

	@Test
	fun globMatchesFileNames() {
		assertThat(ranked("*.kt", "app/a.kt", "b.kt", "app/c.java", "app/d.kts"))
			.containsExactly("app/a.kt", "b.kt")
	}

	@Test
	fun globIgnoresCase() {
		assertThat(ranked("main*.KT", "app/MainActivity.kt")).containsExactly("app/MainActivity.kt")
	}

	@Test
	fun globResultsAreSortedByPathIgnoringCase() {
		assertThat(ranked("*.kt", "z/b.kt", "M.kt", "a/c.kt"))
			.containsExactly("a/c.kt", "M.kt", "z/b.kt")
			.inOrder()
	}

	@Test
	fun globWithSlashMatchesTheRelativePath() {
		assertThat(ranked("app/**/*.xml", "app/src/main/res/layout/a.xml", "lib/src/b.xml", "app/c.kt"))
			.containsExactly("app/src/main/res/layout/a.xml")
	}

	@Test
	fun malformedGlobIsRejected() {
		assertThrows(PatternSyntaxException::class.java) { FileQuery.parse("[ab") }
	}

	@Test
	fun walkListsFilesRelativeToRootSkippingExcludedDirectoriesAtAnyDepth() {
		listOf("app/src/A.kt", "app/build/gen/B.kt", "build/C.kt", ".git/config", "README.md").forEach {
			File(tmp.root, it).apply { parentFile.mkdirs() }.writeText("")
		}

		assertThat(walkProjectFiles(tmp.root, ProjectSearchOptions.PROJECT_ROOT_EXCLUDED_DIR_NAMES))
			.containsExactly("app/src/A.kt", "README.md")
	}

	@Test
	fun walkSkipsSymlinks() {
		File(tmp.root, "app/A.kt").apply { parentFile.mkdirs() }.writeText("")
		Files.createSymbolicLink(File(tmp.root, "linkedDir").toPath(), File(tmp.root, "app").toPath())
		Files.createSymbolicLink(File(tmp.root, "linked.kt").toPath(), File(tmp.root, "app/A.kt").toPath())

		assertThat(walkProjectFiles(tmp.root, emptySet())).containsExactly("app/A.kt")
	}
}
