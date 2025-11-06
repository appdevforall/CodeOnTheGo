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
;                if (fileOrDirectory.isDirectory) {
                    fileOrDirectory.copyRecursively(hiddenFile, overwrite = false)
                } else {
                    fileOrDirectory.copyTo(hiddenFile, overwrite = false)
                    Log.d(TAG, "File copy complete")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Backup creation failed: ${t.message}", t)
            }

            val deletedOriginal = deleteRecursively(fileOrDirectory)

            if (deletedOriginal && hiddenFile.exists()) {
                val deletedBackup = deleteRecursively(hiddenFile)
                if (deletedBackup) {
                    Log.d(TAG, "Backup cleaned up: ${hiddenFile.absolutePath}")
                } else {
                    Log.w(TAG, "Original deleted but backup cleanup failed: ${hiddenFile.absolutePath}")
                }
            }

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
                        if (!deleteRecursively(child)) {
                            Log.w(TAG, "Failed to delete: ${child.absolutePath}")
                            return false
                        }
                    }
                }
            }

            return file.delete().also { deleted ->
                if (!deleted) {
                    Log.e(TAG, "Failed to delete: ${file.absolutePath}")
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException deleting ${file.absolutePath}: ${e.message}", e)
            return false
        } catch (e: IOException) {
            Log.e(TAG, "IOException deleting ${file.absolutePath}: ${e.message}", e)
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error deleting ${file.absolutePath}: ${t.message}", t)
            return false
        }
    }
}
