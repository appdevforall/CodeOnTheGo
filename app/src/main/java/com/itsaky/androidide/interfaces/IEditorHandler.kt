/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.interfaces

import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SaveResult
import com.itsaky.androidide.ui.CodeEditorView
import java.io.File

/**
 * @author Akash Yadav
 */
interface IEditorHandler {
	fun findIndexOfEditorByFile(file: File?): Int

	fun getCurrentEditor(): CodeEditorView?

	fun getEditorAtIndex(index: Int): CodeEditorView?

	fun getEditorForFile(file: File): CodeEditorView?

	suspend fun openFile(file: File): CodeEditorView? = openFile(file, null)

	suspend fun openFile(
		file: File,
		selection: Range?,
	): CodeEditorView?

	fun openFileAndSelect(
		file: File,
		selection: Range?,
	)

	fun openFileAndGetIndex(
		file: File,
		selection: Range?,
	): Int

	fun areFilesModified(): Boolean

	/**
	 * Whether any open file still holds unsaved edits *that a save could actually have written*.
	 *
	 * Not the same question as [areFilesModified], which is a cached flag recomputed only as a side
	 * effect of a successful per-file write, and which counts read-only archive tabs (.zip/.apk and
	 * friends) that `CodeEditorView.save()` never writes. Asking [areFilesModified] after a save to
	 * decide whether the save worked therefore answers "still modified" forever whenever such a tab
	 * is open, which is why this exists.
	 */
	fun hasUnsavedWritableFiles(): Boolean

	fun areFilesSaving(): Boolean

	/**
	 * Save all files.
	 *
	 * @param notify Whether to notify the user about the save event.
	 * @param processResources Whether the resources must be generated after the save operation.
	 * @param progressConsumer A function which consumes the progress of the save operation.
	 * See [saveAllResult] for more details.
	 */
	suspend fun saveAll(
		notify: Boolean = true,
		requestSync: Boolean = true,
		processResources: Boolean = false,
		progressConsumer: ((progress: Int, total: Int) -> Unit)? = null,
	): Boolean

	/**
	 * Save all files asynchronously.
	 *
	 * @param runAfter A callback function which will be run after the save attempt is over, whether
	 * it succeeded or not, receiving `true` iff every file saved without throwing. Callers that act
	 * on the saved state (e.g. proceeding with a git operation) must check this rather than assuming
	 * the callback firing means the save succeeded.
	 * @see saveAll
	 */
	fun saveAllAsync(
		notify: Boolean = true,
		requestSync: Boolean = true,
		processResources: Boolean = false,
		progressConsumer: ((progress: Int, total: Int) -> Unit)? = null,
		runAfter: ((succeeded: Boolean) -> Unit)? = null,
	)

	/**
	 * Save all files and get the [SaveResult].
	 *
	 * @param progressConsumer A function which consumes the progress of the save operation. The first
	 * parameter of the function is the current save progress (saved file count) and the second parameter
	 * is the total file count.
	 */
	suspend fun saveAllResult(progressConsumer: ((progress: Int, total: Int) -> Unit)? = null): SaveResult

	suspend fun saveResult(
		index: Int,
		result: SaveResult,
	)

	fun closeFile(index: Int) = closeFile(index) {}

	fun closeFile(
		index: Int,
		runAfter: () -> Unit,
	)

	fun closeAll() = closeAll {}

	fun closeAll(runAfter: () -> Unit)

	fun closeOthers()

	fun openFAQActivity(htmlData: String)
}
