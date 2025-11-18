package com.itsaky.androidide.viewmodel

import androidx.annotation.IntDef
import androidx.annotation.Keep
import androidx.lifecycle.ViewModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.adapters.EditorBottomSheetTabAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory

class BottomSheetViewModel : ViewModel() {
	data class SheetState(
		val sheetState: Int,
		@TabDef
		val currentTab: Int,
	) {
		companion object {
			val EMPTY = SheetState(BottomSheetBehavior.STATE_COLLAPSED, TAB_BUILD_OUTPUT)
		}
	}

	companion object {
		private val logger = LoggerFactory.getLogger(BottomSheetViewModel::class.java)

		const val TAB_BUILD_OUTPUT = EditorBottomSheetTabAdapter.TAB_BUILD_OUTPUT
		const val TAB_APPLICATION_LOGS = EditorBottomSheetTabAdapter.TAB_APPLICATION_LOGS
		const val TAB_IDE_LOGS = EditorBottomSheetTabAdapter.TAB_IDE_LOGS
		const val TAB_DIAGNOSTICS = EditorBottomSheetTabAdapter.TAB_DIAGNOSTICS
		const val TAB_SEARCH_RESULT = EditorBottomSheetTabAdapter.TAB_SEARCH_RESULTS
		const val TAB_DEBUGGER = EditorBottomSheetTabAdapter.TAB_DEBUGGER
        const val TAB_AGENT = EditorBottomSheetTabAdapter.TAB_AGENT
	}

	@Keep
	@IntDef(
		TAB_BUILD_OUTPUT,
		TAB_APPLICATION_LOGS,
		TAB_IDE_LOGS,
		TAB_DIAGNOSTICS,
		TAB_SEARCH_RESULT,
		TAB_DEBUGGER,
        TAB_AGENT,
	)
	@Retention(AnnotationRetention.SOURCE)
	annotation class TabDef

	private val _sheetState = MutableStateFlow(SheetState.EMPTY)

	val sheetState = _sheetState.asStateFlow()

	val sheetBehaviorState: Int
		get() = sheetState.value.sheetState

	val currentTab: Int
		get() = sheetState.value.currentTab

	fun setSheetState(
		sheetState: Int = this.sheetState.value.sheetState,
		@TabDef currentTab: Int = this.sheetState.value.currentTab,
	) {
		val newState = SheetState(sheetState, currentTab)
		logger.debug("new SheetState: {}", newState)
		_sheetState.update { newState }
	}
}
