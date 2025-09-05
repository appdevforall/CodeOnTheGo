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
	@ConsistentCopyVisibility
	data class ConnectionState internal constructor(
		val output: StringBuilder = StringBuilder(),
		var error: Throwable? = null,
	) {
		fun appendOutput(output: CharSequence) {
			this.output.apply {
				append(output)
				append(System.lineSeparator())
			}
		}

		fun clearOutput() {
			this.output.clear()
		}

		fun recordConnectionFailure(err: Throwable? = null) {
			this.error = err
		}
	}

	private val _currentView = MutableStateFlow(WADBPermissionFragment.VIEW_PAIRING)
	private val _connectionStatus = MutableStateFlow("")

	val currentView: StateFlow<Int>
		get() = _currentView.asStateFlow()

	val connectionStatus: StateFlow<String>
		get() = _connectionStatus.asStateFlow()

	fun setCurrentView(currentView: Int) {
		_currentView.update { currentView }
	}

	fun setConnectionStatus(connectionStatus: String) {
		_connectionStatus.update { connectionStatus }
	}
}
