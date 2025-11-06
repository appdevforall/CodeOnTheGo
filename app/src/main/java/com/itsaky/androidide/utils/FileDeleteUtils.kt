package com.itsaky.androidide.utils

import android.util.Log
import io.sentry.Sentry
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

object FileDeleteUtils {
    private const val TAG = "FileDeleteUtils"


    suspend fun deleteRecursive(fileOrDirectory: File): Boolean = withContext(Dispatchers.IO) {
        val hiddenFile = File(
            fileOrDirectory.parentFile,
            ".${fileOrDirectory.name}"
        )

        if (hiddenFile.exists() && !fileOrDirectory.exists()) {
            Log.w(TAG, "Cleaning up previous failed deletion: ${hiddenFile.absolutePath}")
            deleteRecursively(hiddenFile)
        }

        if (fileOrDirectory.exists()) {
            val renamed = runCatching {
                fileOrDirectory.renameTo(hiddenFile)
            }.onFailure { t ->
                Log.e(TAG, "Failed to rename ${fileOrDirectory.absolutePath} to hidden: ${t.message}", t)
                Sentry.captureException(t)
            }.getOrDefault(false)

            if (!renamed) {
                return@withContext false
            }
        }

        val deleted = deleteRecursively(hiddenFile)

        if (!deleted) {
            Log.w(TAG, "Hidden directory remains after deletion failure: ${hiddenFile.absolutePath}")
        }

        deleted
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
            Sentry.captureException(e)
            return false
        } catch (e: IOException) {
            Log.e(TAG, "IOException deleting ${file.absolutePath}: ${e.message}", e)
            Sentry.captureException(e)
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error deleting ${file.absolutePath}: ${t.message}", t)
            Sentry.captureException(t)
            return false
        }
    }
}
