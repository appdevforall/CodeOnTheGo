package com.itsaky.androidide.services.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import rikka.shizuku.server.ShizukuServerConstants.ACTION_FOREGROUND_APP_CHANGED
import rikka.shizuku.server.ShizukuServerConstants.EXTRA_FOREGROUND_PACKAGES
import rikka.shizuku.server.ShizukuServerConstants.EXTRA_FOREGROUND_PID
import rikka.shizuku.server.ShizukuServerConstants.EXTRA_FOREGROUND_UID

data class ForegroundAppState(
	val uid: Int,
	val pid: Int,
	val packageNames: Array<String>,
) {
	companion object {
		val EMPTY = ForegroundAppState(-1, -1, emptyArray())
	}

	override fun toString(): String = "ForegroundAppState(uid=$uid, pid=$pid, packageNames=${packageNames.contentToString()})"

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as ForegroundAppState

		if (uid != other.uid) return false
		if (pid != other.pid) return false
		if (!packageNames.contentEquals(other.packageNames)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = uid
		result = 31 * result + pid
		result = 31 * result + packageNames.contentHashCode()
		return result
	}
}

/**
 * @author Akash Yadav
 */
class ForegroundAppReceiver : BroadcastReceiver() {
	companion object {
		private val logger = LoggerFactory.getLogger(ForegroundAppReceiver::class.java)
		private val _foregroundAppState = MutableStateFlow(ForegroundAppState.EMPTY)
		val foregroundAppState = _foregroundAppState.asStateFlow()
	}

	override fun onReceive(context: Context?, intent: Intent?) {
		if (intent?.action != ACTION_FOREGROUND_APP_CHANGED) {
			logger.error("onReceive: intent.action={} is not supported", intent?.action ?: "null")
			return
		}

		val uid = intent.getIntExtra(EXTRA_FOREGROUND_UID, -1)
		val pid = intent.getIntExtra(EXTRA_FOREGROUND_PID, -1)
		val packageNames =
			intent.getStringArrayExtra(EXTRA_FOREGROUND_PACKAGES) ?: emptyArray<String>()
		logger.debug(
			"onReceive: uid={}, pid={}, packageName={}", uid, pid, packageNames.contentToString()
		)

		if (packageNames.isEmpty()) {
			return
		}

		val newState = ForegroundAppState(uid, pid, packageNames)
		_foregroundAppState.update { newState }
	}
}