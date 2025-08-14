
package com.itsaky.androidide.utils

import android.os.Environment
import java.io.File

private const val EXPERIMENTS_FILE_NAME = "CodeOnTheGo.exp"

fun isExperimentsEnabled(): Boolean {
    val experimentsFile = File(Environment.getExternalStorageDirectory(), "Download/$EXPERIMENTS_FILE_NAME")
    return experimentsFile.exists()
}