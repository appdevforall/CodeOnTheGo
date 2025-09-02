package com.itsaky.androidide.viewmodel

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.fragments.output.BuildOutputFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BottomSheetViewModel : ViewModel() {
	private val _state = MutableStateFlow(BottomSheetBehavior.STATE_COLLAPSED)
	private val _currentTab = MutableStateFlow<Class<out Fragment>>(BuildOutputFragment::class.java)
	private val _isDebuggerUiVisible = MutableStateFlow(true)

	val state: StateFlow<Int>
		get() = _state.asStateFlow()

	val currentTab: StateFlow<Class<out Fragment>>
		get() = _currentTab.asStateFlow()

	val isDebuggerUiVisible: StateFlow<Boolean>
		get() = _isDebuggerUiVisible.asStateFlow()

	fun setState(state: Int) {
		val currentState = this.state.value
		if (state == currentState) {
			return
		}

		_state.update { state }
	}

	fun <T : Fragment> setCurrentTab(tab: Class<T>) {
		_currentTab.update { tab }
	}

	fun setDebuggerUiVisibility(isVisible: Boolean) {
		_isDebuggerUiVisible.update { isVisible }
	}
}
