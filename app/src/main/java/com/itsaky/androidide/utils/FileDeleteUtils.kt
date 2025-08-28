package com.itsaky.androidide.utils

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

object FileDeleteUtils {
    private const val TAG = "FileDeleteUtils"


    fun deleteRecursive(
        fileOrDirectory: File,
        onDeleted: (success: Boolean) -> Unit = {}
    ) {
        val hiddenFile = File(
            fileOrDirectory.parentFile,
            ".${fileOrDirectory.name}"
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {

                if (fileOrDirectory.isDirectory) {
                    val copied = fileOrDirectory.copyRecursively(hiddenFile, overwrite = false)
                } else {
                    fileOrDirectory.copyTo(hiddenFile, overwrite = false)
                    Log.d(TAG, "File copy complete")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Copy failed: ${t.message}", t)
            }

            val deletedOriginal = deleteRecursively(fileOrDirectory)

            withContext(Dispatchers.Main) {
                onDeleted(deletedOriginal)
            }
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        try {
            if (file.isDirectory) {
                val children = file.listFiles()
                if (children != null) {
                    for (child in children) {
                        deleteRecursively(child)
                    }
                }
            }

            val deleted = file.delete()
            return deleted || !file.exists()

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException deleting ${file.absolutePath}: ${e.message}", e)
            return !file.exists()
        } catch (e: IOException) {
            Log.e(TAG, "IOException deleting ${file.absolutePath}: ${e.message}", e)
            return !file.exists()
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error deleting ${file.absolutePath}: ${t.message}", t)
            return !file.exists()
        }
    }
}
