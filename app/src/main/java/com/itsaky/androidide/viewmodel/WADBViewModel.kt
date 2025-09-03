package com.itsaky.androidide.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import com.itsaky.androidide.fragments.debug.WADBPermissionFragment
import com.itsaky.androidide.tasks.runOnUiThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@RequiresApi(Build.VERSION_CODES.R)
class WADBViewModel : ViewModel() {
	@ConsistentCopyVisibility
	data class ConnectionState internal constructor(
		val output: StringBuilder = StringBuilder(),
		var error: Throwable? = null,
	) {
		companion object {
			val EMPTY = ConnectionState()
		}
	}

	private val _connectionState = MutableStateFlow(ConnectionState.EMPTY.copy())
	private val _currentView = MutableStateFlow(WADBPermissionFragment.VIEW_PAIRING)
	private val _connectionStatus = MutableStateFlow("")

	val currentView: StateFlow<Int>
		get() = _currentView.asStateFlow()

	val connectionState: StateFlow<ConnectionState>
		get() = _connectionState

	val connectionStatus: StateFlow<String>
		get() = _connectionStatus.asStateFlow()

	fun setCurrentView(currentView: Int) {
		_currentView.update { currentView }
	}

	fun setConnectionStatus(connectionStatus: String) {
		_connectionStatus.update { connectionStatus }
	}

	fun appendOutput(output: CharSequence) {
		val currentState = _connectionState.value
		val outputBuilder =
			currentState.output.apply {
				append(output)
				append(System.lineSeparator())
			}

		val newState = currentState.copy(output = outputBuilder)
		_connectionState.update { newState }
	}

	fun recordConnectionFailure(err: Throwable? = null) =
		runOnUiThread {
			val currentState = _connectionState.value
			val newState = currentState.copy(error = err)
			_connectionState.update { newState }
		}
}
