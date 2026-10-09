package com.itsaky.androidide.search.replace

import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.eventbus.events.file.FileContentChangedEvent
import com.itsaky.androidide.models.SearchResult
import io.github.rosemoe.sora.text.Content
import org.greenrobot.eventbus.EventBus
import java.io.File

class ReplaceCoordinator(
	private val editorFor: (File) -> IDEEditor?,
	private val replacer: ProjectReplacer = ProjectReplacer(),
) {
	data class Report(
		val replacedMatches: Int,
		val changedFiles: List<File>,
		val skipped: Map<File, SkipReason>,
		val failed: List<FileFailure>,
	)

	private class TabEntry(
		val file: File,
		val original: String,
		val postReplace: String,
	)

	private var pendingTabs: List<TabEntry> = emptyList()
	private var pendingDisk: List<DiskEntry> = emptyList()

	var isBusy: Boolean = false
		private set

	val canUndo: Boolean
		get() = pendingTabs.isNotEmpty() || pendingDisk.isNotEmpty()

	fun discardUndo() {
		pendingTabs = emptyList()
		pendingDisk = emptyList()
	}

	suspend fun replace(session: ReplaceSession): Report = exclusive { replaceExclusively(session) }

	suspend fun undo(): Report = exclusive { undoExclusively() }

	private suspend fun exclusive(block: suspend () -> Report): Report {
		check(!isBusy) { "A replace or undo is already running" }
		isBusy = true
		try {
			return block()
		} finally {
			isBusy = false
		}
	}

	private suspend fun replaceExclusively(session: ReplaceSession): Report {
		discardUndo()
		val skipped = linkedMapOf<File, SkipReason>()
		val tabs = mutableListOf<TabEntry>()
		val closed = mutableListOf<FileEdit>()
		var replacedMatches = 0
		session.includedEdits().forEach { edit ->
			val editor = editorFor(edit.file)
			if (editor == null) {
				closed.add(edit)
				return@forEach
			}
			val before = editor.text.toString()
			when (TextReplacement.apply(before, edit.matches, session.replacement)) {
				TextReplacement.Result.Stale -> {
					skipped[edit.file] = SkipReason.CHANGED_SINCE_SEARCH
				}

				is TextReplacement.Result.Applied -> {
					applyToBuffer(editor.text, edit.matches, session.replacement)
					tabs.add(TabEntry(edit.file, before, editor.text.toString()))
					replacedMatches += edit.matches.size
				}
			}
		}
		val disk = replacer.replaceOnDisk(closed, session.replacement)
		skipped.putAll(disk.skipped)
		replacedMatches += closed.filter { it.file in disk.replaced }.sumOf { it.matches.size }
		disk.replaced.forEach { EventBus.getDefault().post(FileContentChangedEvent(it)) }
		pendingTabs = tabs
		pendingDisk = disk.undo
		return Report(replacedMatches, tabs.map { it.file } + disk.replaced, skipped, disk.failed)
	}

	private suspend fun undoExclusively(): Report {
		val skipped = linkedMapOf<File, SkipReason>()
		val restoredTabs = mutableListOf<File>()
		pendingTabs.forEach { entry ->
			val editor = editorFor(entry.file)
			when (OpenTabUndo.decide(editor?.text?.toString(), entry.postReplace)) {
				OpenTabUndo.Decision.RESTORE -> {
					replaceAll(editor!!.text, entry.original)
					restoredTabs.add(entry.file)
				}

				OpenTabUndo.Decision.CHANGED -> {
					skipped[entry.file] = SkipReason.CHANGED_SINCE_REPLACE
				}

				OpenTabUndo.Decision.CLOSED -> {
					skipped[entry.file] = SkipReason.TAB_CLOSED
				}
			}
		}
		val toRestore = mutableListOf<DiskEntry>()
		val reopened = mutableListOf<Pair<DiskEntry, IDEEditor>>()
		pendingDisk.forEach { entry ->
			val editor = editorFor(entry.file)
			when (OpenTabUndo.decide(editor?.text?.toString(), String(entry.written, Charsets.UTF_8))) {
				OpenTabUndo.Decision.CLOSED -> {
					toRestore.add(entry)
				}

				OpenTabUndo.Decision.RESTORE -> {
					toRestore.add(entry)
					reopened.add(entry to editor!!)
				}

				OpenTabUndo.Decision.CHANGED -> {
					skipped[entry.file] = SkipReason.CHANGED_SINCE_REPLACE
				}
			}
		}
		val disk = replacer.undoOnDisk(toRestore)
		reopened
			.filter { (entry, _) -> entry.file in disk.restored }
			.forEach { (entry, editor) ->
				replaceAll(editor.text, String(entry.original, Charsets.UTF_8))
				editor.markUnmodified()
			}
		skipped.putAll(disk.skipped)
		disk.restored.forEach { EventBus.getDefault().post(FileContentChangedEvent(it)) }
		discardUndo()
		return Report(0, restoredTabs + disk.restored, skipped, disk.failed)
	}

	private fun applyToBuffer(
		text: Content,
		matches: List<SearchResult>,
		replacement: String,
	) {
		text.beginBatchEdit()
		matches
			.sortedWith(compareByDescending<SearchResult> { it.start.line }.thenByDescending { it.start.column })
			.forEach { match -> text.replace(match.start.line, match.start.column, match.end.line, match.end.column, replacement) }
		text.endBatchEdit()
	}

	private fun replaceAll(
		text: Content,
		newText: String,
	) {
		val lastLine = text.lineCount - 1
		text.beginBatchEdit()
		text.replace(0, 0, lastLine, text.getColumnCount(lastLine), newText)
		text.endBatchEdit()
	}
}
