package com.itsaky.androidide.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.utils.ApkInstaller
import com.itsaky.androidide.utils.SingleSessionCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

/**
 * @author Akash Yadav
 */
class ApkInstallationViewModel : ViewModel() {

	companion object {
		private val logger = LoggerFactory.getLogger(ApkInstallationViewModel::class.java)
	}

	/**
	 * The current state of the APK installation.
	 */
	sealed class SessionState {

		/**
		 * The APK installation is idle.
		 */
		object Idle : SessionState()

		/**
		 * The APK installation session is in progress.
		 */
		data class InProgress(
			val sessionId: Int,
			val progress: Int
		) : SessionState()

		/**
		 * The APK installation session is complete.
		 */
		data class Finished(val sessionId: Int, val isSuccess: Boolean) : SessionState()
	}

	private val callback = object : SingleSessionCallback() {
		override fun onCreated(sessionId: Int) {
			logger.debug("onCreated: sessionId={}", sessionId)

			setSessionState(SessionState.InProgress(sessionId = sessionId, progress = 0))
		}

		override fun onProgressChanged(sessionId: Int, progress: Float) {
			logger.debug("onProgressChanged: sessionId={}, progress={}", sessionId, progress)

			setSessionState(
				SessionState.InProgress(
					sessionId = sessionId,
					progress = (progress * 100).toInt()
				)
			)
		}

		override fun onFinished(sessionId: Int, success: Boolean) {
			logger.debug("onFinished: sessionId={}, success={}", sessionId, success)

			setSessionState(SessionState.Finished(sessionId = sessionId, isSuccess = success))
		}
	}

	private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)

	/**
	 * The current state of the APK installation.
	 */
	val sessionState = _sessionState.asStateFlow()

	/**
	 * Sets the current state of the APK installation.
	 */
	fun setSessionState(newState: SessionState) {
		this._sessionState.update { newState }
	}

	/**
	 * Reset the state of the APK installation.
	 *
	 * Call this after handling [SessionState.Finished] state.
	 */
	fun resetState() {
		setSessionState(SessionState.Idle)
	}

	/**
	 * Installs the APK file.
	 *
	 * @param context The context.
	 * @param file The APK file to install.
	 */
	fun installApk(
		context: Context,
		file: File,
	) {
		val packageInstaller = context.packageManager.packageInstaller
		packageInstaller.registerSessionCallback(callback)

		viewModelScope.launch {
			ApkInstaller.installApk(context, file)
		}
	}

	/**
	 * Reloads the installation status.
	 *
	 * @return The session ID if the installation is in progress, otherwise -1.
	 */
	fun reloadStatus(context: Context): Int {
		val state = sessionState.value
		val sessionId = when (state) {
			SessionState.Idle -> return -1
			is SessionState.InProgress -> state.sessionId
			is SessionState.Finished -> state.sessionId
		}

		if (sessionId == -1) {
			// we're in an invalid state here, fall back to idle state
			logger.debug(
				"Invalid package installer session ID: {}. Falling back to IDLE state.",
				sessionId
			)
			setSessionState(SessionState.Idle)
			return -1
		}

		val packageInstaller = context.packageManager.packageInstaller
		val session = packageInstaller.mySessions.firstOrNull { it.sessionId == sessionId }
		if (session == null) {
			// our current session state refers to a non-existing session
			logger.debug(
				"PackageInstaller Session with ID {} not found. Falling back to IDLE state.",
				sessionId
			)
			setSessionState(SessionState.Idle)
			return -1
		}

		if (!session.isActive) {
			// our current session state refers to a non-active session
			setSessionState(SessionState.Idle)
			logger.debug(
				"PackageInstaller Session with ID {} is not active. Falling back to IDLE state.",
				sessionId
			)
			return -1
		}

		return sessionId
	}

	/**
	 * Destroys the APK installation session.
	 */
	fun destroy(context: Context) {
		val sessionId = reloadStatus(context)
		if (sessionId == -1) {
			return
		}

		try {
			val packageInstaller = context.packageManager.packageInstaller
			packageInstaller.abandonSession(sessionId)
		} catch (e: Exception) {
			logger.error("Failed to abandon session with ID: {}", sessionId, e)
		} finally {
			setSessionState(SessionState.Idle)
		}
	}
}