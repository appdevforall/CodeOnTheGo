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

import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.itsaky.androidide.databinding.LayoutLogFilterBarBinding
import com.itsaky.androidide.utils.ILogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumSet

/**
 * Wires a [layout_log_filter_bar][LayoutLogFilterBarBinding] to a filter-change callback:
 * level chips apply immediately, text input is debounced.
 *
 * @param showLevelChips Whether the level chip row is shown (outputs without level
 *   metadata, like the build output, only get the text filter).
 * @param onFilterChanged Called with the enabled levels and the (untrimmed) filter text.
 */
class LogFilterBarController(
	private val binding: LayoutLogFilterBarBinding,
	coroutineScope: CoroutineScope,
	showLevelChips: Boolean,
	initialText: String,
	initialLevels: Set<ILogger.Level>,
	private val onFilterChanged: (levels: Set<ILogger.Level>, text: String) -> Unit,
) {
	companion object {
		private const val FILTER_TEXT_DEBOUNCE_MS = 250L
	}

	private val chipsByLevel =
		mapOf(
			ILogger.Level.VERBOSE to binding.chipLevelVerbose,
			ILogger.Level.DEBUG to binding.chipLevelDebug,
			ILogger.Level.INFO to binding.chipLevelInfo,
			ILogger.Level.WARNING to binding.chipLevelWarning,
			ILogger.Level.ERROR to binding.chipLevelError,
		)

	private var textDebounceJob: Job? = null

	init {
		binding.levelChipsScroll.isVisible = showLevelChips
		binding.filterInput.setText(initialText)
		chipsByLevel.forEach { (level, chip) ->
			chip.isChecked = level in initialLevels
			chip.setOnCheckedChangeListener { _, _ -> notifyFilterChanged() }
		}
		binding.filterInput.doAfterTextChanged {
			textDebounceJob?.cancel()
			textDebounceJob =
				coroutineScope.launch {
					delay(FILTER_TEXT_DEBOUNCE_MS)
					notifyFilterChanged()
				}
		}
		binding.closeFilterBar.setOnClickListener { hide() }
	}

	fun toggle() {
		binding.root.isVisible = !binding.root.isVisible
	}

	fun hide() {
		binding.root.isVisible = false
	}

	private fun notifyFilterChanged() {
		val levels = EnumSet.noneOf(ILogger.Level::class.java)
		chipsByLevel.forEach { (level, chip) ->
			if (chip.isChecked) {
				levels.add(level)
			}
		}
		onFilterChanged(
			levels,
			binding.filterInput.text
				?.toString()
				.orEmpty(),
		)
	}
}
