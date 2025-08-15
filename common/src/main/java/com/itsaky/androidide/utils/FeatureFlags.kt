
package com.itsaky.androidide.utils

import android.os.Environment
import java.io.File

private const val EXPERIMENTS_FILE_NAME = "CodeOnTheGo.exp"

fun isExperimentsEnabled(): Boolean {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val experimentsFile = File(downloadsDir, EXPERIMENTS_FILE_NAME)
    return experimentsFile.exists()
}