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

package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * @author Akash Yadav
 */
class EmptyStateFragmentViewModel : ViewModel() {
	private val _isEmpty = MutableStateFlow(true)
	private val _isSourceEmpty = MutableStateFlow(true)
	private val _emptyMessage = MutableStateFlow<CharSequence>("")

	/** Whether the empty-state layout (not the content layout) should be shown. */
	val isEmpty = _isEmpty.asStateFlow()

	/**
	 * Whether the underlying data source has no content at all. Unlike [isEmpty], this is
	 * independent of any filter UI, so it is the signal to gate content-dependent actions on.
	 */
	val isSourceEmpty = _isSourceEmpty.asStateFlow()

	val emptyMessage = _emptyMessage.asStateFlow()

	/** Sets both [isEmpty] and [isSourceEmpty]; they only diverge when a filter UI is active. */
	fun setEmpty(isEmpty: Boolean) {
		setEmptyState(isEmpty = isEmpty, isSourceEmpty = isEmpty)
	}

	fun setEmptyState(
		isEmpty: Boolean,
		isSourceEmpty: Boolean,
	) {
		_isEmpty.update { isEmpty }
		_isSourceEmpty.update { isSourceEmpty }
	}

	fun setEmptyMessage(emptyMessage: CharSequence) {
		_emptyMessage.update { emptyMessage }
	}
}
