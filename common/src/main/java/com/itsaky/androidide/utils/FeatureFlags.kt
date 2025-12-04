package com.itsaky.androidide.utils

import android.os.Environment
import java.io.File

object FeatureFlags {
	private const val EXPERIMENTS_FILE_NAME = "CodeOnTheGo.exp"
	private const val LOGD_FILE_NAME = "CodeOnTheGo.logd"
    private const val EMULATOR_FILE_NAME = "S153.txt"
    private const val REPRIEVE_FILE_NAME = "CodeOnTheGo.a3s19"

	private val downloadsDir =
		Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

	fun isExperimentsEnabled(): Boolean {
		val experimentsFile = File(downloadsDir, EXPERIMENTS_FILE_NAME)
		return experimentsFile.exists()
	}

	fun isDebugLoggingEnabled(): Boolean {
		val logdFile = File(downloadsDir, LOGD_FILE_NAME)
		return logdFile.exists()
	}

    fun isReprieveEnabled(): Boolean {
        val moratoriumFile = File(downloadsDir, REPRIEVE_FILE_NAME)
        return moratoriumFile.exists()
    }

    fun isEmulatorUseEnabled(): Boolean {
        val emulatorFile = File(downloadsDir, EMULATOR_FILE_NAME)
        return emulatorFile.exists()
    }
}