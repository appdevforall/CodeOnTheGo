package com.itsaky.androidide.search.replace

import com.itsaky.androidide.models.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView

data class FileEdit(
	val file: File,
	val matches: List<SearchResult>,
)

enum class SkipReason { CHANGED_SINCE_SEARCH, CHANGED_SINCE_REPLACE, TAB_CLOSED }

data class FileFailure(
	val file: File,
	val message: String,
)

class DiskEntry(
	val file: File,
	val original: ByteArray,
	val written: ByteArray,
)

data class DiskReplaceOutcome(
	val replaced: List<File>,
	val skipped: Map<File, SkipReason>,
	val failed: List<FileFailure>,
	val undo: List<DiskEntry>,
)

data class UndoOutcome(
	val restored: List<File>,
	val skipped: Map<File, SkipReason>,
	val failed: List<FileFailure>,
)

class ProjectReplacer(
	private val io: CoroutineDispatcher = Dispatchers.IO,
) {
	suspend fun replaceOnDisk(
		edits: List<FileEdit>,
		replacement: String,
	): DiskReplaceOutcome =
		withContext(io) {
			val replaced = mutableListOf<File>()
			val skipped = linkedMapOf<File, SkipReason>()
			val failed = mutableListOf<FileFailure>()
			val undo = mutableListOf<DiskEntry>()
			edits.forEach { edit ->
				try {
					val original = Files.readAllBytes(edit.file.toPath())
					when (val result = TextReplacement.apply(String(original, Charsets.UTF_8), edit.matches, replacement)) {
						TextReplacement.Result.Stale -> {
							skipped[edit.file] = SkipReason.CHANGED_SINCE_SEARCH
						}

						is TextReplacement.Result.Applied -> {
							val written = result.newText.toByteArray(Charsets.UTF_8)
							if (!writeAtomically(edit.file, written, expected = original)) {
								skipped[edit.file] = SkipReason.CHANGED_SINCE_SEARCH
								return@forEach
							}
							replaced.add(edit.file)
							undo.add(DiskEntry(edit.file, original, written))
						}
					}
				} catch (e: IOException) {
					failed.add(FileFailure(edit.file, e.message ?: e.javaClass.simpleName))
				}
			}
			DiskReplaceOutcome(replaced, skipped, failed, undo)
		}

	suspend fun undoOnDisk(entries: List<DiskEntry>): UndoOutcome =
		withContext(io) {
			val restored = mutableListOf<File>()
			val skipped = linkedMapOf<File, SkipReason>()
			val failed = mutableListOf<FileFailure>()
			entries.forEach { entry ->
				try {
					if (!writeAtomically(entry.file, entry.original, expected = entry.written)) {
						skipped[entry.file] = SkipReason.CHANGED_SINCE_REPLACE
						return@forEach
					}
					restored.add(entry.file)
				} catch (e: IOException) {
					failed.add(FileFailure(entry.file, e.message ?: e.javaClass.simpleName))
				}
			}
			UndoOutcome(restored, skipped, failed)
		}

	private fun writeAtomically(
		file: File,
		bytes: ByteArray,
		expected: ByteArray,
	): Boolean {
		val target = file.toPath()
		val temp = File.createTempFile(".${file.name}.", ".replace", file.parentFile)
		try {
			Files.write(temp.toPath(), bytes)
			if (Files.getFileAttributeView(target, PosixFileAttributeView::class.java) != null) {
				Files.setPosixFilePermissions(temp.toPath(), Files.getPosixFilePermissions(target))
			}
			if (!Files.readAllBytes(target).contentEquals(expected)) {
				return false
			}
			Files.move(temp.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			return true
		} finally {
			temp.delete()
		}
	}
}
