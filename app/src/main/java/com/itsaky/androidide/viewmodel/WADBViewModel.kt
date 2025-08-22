package com.itsaky.androidide.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import com.itsaky.androidide.fragments.debug.WADBPermissionFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@RequiresApi(Build.VERSION_CODES.R)
class WADBViewModel : ViewModel() {
	class ConnectionState(
		val output: StringBuilder = StringBuilder(),
		var error: Throwable? = null,
	)

	private val _currentView = MutableStateFlow(WADBPermissionFragment.VIEW_PAIRING)
	private val connectionState = MutableStateFlow(ConnectionState())
	private val _connectionStatus = MutableStateFlow("")

	val currentView: StateFlow<Int>
		get() = _currentView.asStateFlow()

	val output: StateFlow<ConnectionState>
		get() = connectionState.asStateFlow()

	val connectionStatus: StateFlow<String>
		get() = _connectionStatus.asStateFlow()

	fun setCurrentView(currentView: Int) {
		_currentView.update { currentView }
	}

	fun setConnectionStatus(connectionStatus: String) {
		_connectionStatus.update { connectionStatus }
	}

	fun appendOutput(output: CharSequence) {
		connectionState.update { state ->
			state.output.append(output)
			state.output.append(System.lineSeparator())
			state
		}
	}

	fun recordConnectionFailure(err: Throwable? = null) {
		connectionState.update { state ->
			state.error = err
			state
		}
	}
}
