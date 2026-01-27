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
package com.itsaky.androidide.fragments.output

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.R
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.idetooltips.TooltipTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BuildOutputFragment : NonEditableEditorFragment() {
	override val currentEditor: IDEEditor? get() = editor

	private val logChannel = Channel<String>(Channel.UNLIMITED)

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		editor?.tag = TooltipTag.PROJECT_BUILD_OUTPUT
		emptyStateViewModel.setEmptyMessage(getString(R.string.msg_emptyview_buildoutput))

		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
			processLogs()
		}
	}

	override fun onDestroyView() {
		editor?.release()
		super.onDestroyView()
	}

	fun appendOutput(output: String?) {
		if (!output.isNullOrEmpty()) {
			logChannel.trySend(output)
		}
	}

	/**
	 * Ensures the string ends with a newline character (`\n`).
	 * Useful for maintaining correct formatting when concatenating log lines.
	 */
	private fun String.ensureNewline(): String =
		if (endsWith('\n')) this else "$this\n"

	/**
	 * Immediately drains (consumes) all available messages from the channel into the [buffer].
	 *
	 * This is a **non-blocking** operation that enables batching, grouping hundreds of pending lines
	 * into a single memory operation to avoid saturating the UI queue.
	 */
	private fun ReceiveChannel<String>.drainTo(buffer: StringBuilder) {
		var result = tryReceive()
		while (result.isSuccess) {
			val line = result.getOrNull()
			if (!line.isNullOrEmpty()) {
				buffer.append(line.ensureNewline())
			}
			result = tryReceive()
		}
	}

	/**
	 * Main log orchestrator: Consumes, Batches, and Dispatches.
	 *
	 * 1. Suspends (zero CPU usage) until the first log arrives.
	 * 2. Wakes up and drains the entire queue (Batching).
	 * 3. Sends the complete block to the UI in a single pass.
	 */
	private suspend fun processLogs() = with(StringBuilder()) {
		for (firstLine in logChannel) {
			append(firstLine.ensureNewline())
			logChannel.drainTo(this)

			if (isNotEmpty()) {
				val batchText = toString()
				clear()
				flushToEditor(batchText)
			}
		}
	}

	/**
	 * Performs the safe UI update on the Main Thread.
	 *
	 * Uses [IDEEditor.awaitLayout] to guarantee the editor has physical dimensions (width > 0)
	 * before attempting to insert text, preventing the Sora library's `ArrayIndexOutOfBoundsException`.
	 */
	private suspend fun flushToEditor(text: String) = withContext(Dispatchers.Main) {
		editor?.run {
			awaitLayout(onForceVisible = {
				emptyStateViewModel.setEmpty(false)
			})

			appendBatch(text)

			emptyStateViewModel.setEmpty(false)
		}
	}
}
