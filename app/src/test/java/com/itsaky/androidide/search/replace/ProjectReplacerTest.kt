package com.itsaky.androidide.search.replace

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.ProjectSearchOptions
import com.itsaky.androidide.utils.RecursiveFileSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectReplacerTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val replacer = ProjectReplacer(Dispatchers.Unconfined)

	private fun write(
		name: String,
		text: String,
	) = File(tmp.root, name).apply { writeText(text) }

	private fun edits(query: String): List<FileEdit> =
		RecursiveFileSearcher
			.search(query, emptyList(), listOf(tmp.root), ProjectSearchOptions(matchCase = true))
			.map { (file, matches) -> FileEdit(file, matches) }

	@Test
	fun writesReplacementToDisk() =
		runBlocking {
			val a = write("A.java", "url = \"/server33/\"")
			val outcome = replacer.replaceOnDisk(edits("/server33/"), "/server34/")
			assertThat(outcome.replaced).containsExactly(a)
			assertThat(a.readText()).isEqualTo("url = \"/server34/\"")
		}

	@Test
	fun fileChangedSinceSearchIsSkippedAndUntouched() =
		runBlocking {
			val a = write("A.java", "needle")
			val found = edits("needle")
			a.writeText("changed")
			val outcome = replacer.replaceOnDisk(found, "x")
			assertThat(outcome.skipped).containsExactly(a, SkipReason.CHANGED_SINCE_SEARCH)
			assertThat(a.readText()).isEqualTo("changed")
		}

	@Test
	fun deletedFileFailsAndOthersStillReplace() =
		runBlocking {
			val a = write("A.java", "needle")
			val b = write("B.java", "needle")
			val found = edits("needle")
			a.delete()
			val outcome = replacer.replaceOnDisk(found, "x")
			assertThat(outcome.failed.map { it.file }).containsExactly(a)
			assertThat(outcome.replaced).containsExactly(b)
			assertThat(a.exists()).isFalse()
			assertThat(b.readText()).isEqualTo("x")
		}

	@Test
	fun undoRestoresOriginalBytesExactly() =
		runBlocking {
			val original = "a\r\nneedle\r\n"
			val a = write("A.java", original)
			val outcome = replacer.replaceOnDisk(edits("needle"), "x")
			val undo = replacer.undoOnDisk(outcome.undo)
			assertThat(undo.restored).containsExactly(a)
			assertThat(a.readText()).isEqualTo(original)
		}

	@Test
	fun undoSkipsFileEditedAfterReplace() =
		runBlocking {
			val a = write("A.java", "needle")
			val outcome = replacer.replaceOnDisk(edits("needle"), "x")
			a.writeText("user edit")
			val undo = replacer.undoOnDisk(outcome.undo)
			assertThat(undo.skipped).containsExactly(a, SkipReason.CHANGED_SINCE_REPLACE)
			assertThat(a.readText()).isEqualTo("user edit")
		}

	@Test
	fun undoIgnoresTimestampChangesWhenContentIsUntouched() =
		runBlocking {
			val a = write("A.java", "needle")
			val outcome = replacer.replaceOnDisk(edits("needle"), "x")
			a.setLastModified(a.lastModified() - 60_000)
			val undo = replacer.undoOnDisk(outcome.undo)
			assertThat(undo.restored).containsExactly(a)
			assertThat(a.readText()).isEqualTo("needle")
		}
}
