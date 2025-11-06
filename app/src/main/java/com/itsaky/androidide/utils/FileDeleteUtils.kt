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

        if (hiddenFile.exists()) {
            deleteRecursively(hiddenFile)
            Log.w(TAG, "Cleaned up stale backup: ${hiddenFile.absolutePath}")
        }

        val backupCreated = runCatching {
            if (fileOrDirectory.isDirectory) {
                fileOrDirectory.copyRecursively(hiddenFile, overwrite = false)
            } else {
                fileOrDirectory.copyTo(hiddenFile, overwrite = false)
            }
        }.onFailure { t ->
            Log.e(TAG, "Backup creation failed: ${t.message}", t)
            Sentry.captureException(t)
        }.isSuccess

        if (!backupCreated) {
            return@withContext false
        }

        val deletedOriginal = deleteRecursively(fileOrDirectory)

        if (deletedOriginal) {
            val deletedBackup = deleteRecursively(hiddenFile)
            if (!deletedBackup) {
                Log.w(TAG, "Backup cleanup failed: ${hiddenFile.absolutePath}")
            }
        } else {
            runCatching {
                if (hiddenFile.isDirectory) {
                    hiddenFile.copyRecursively(fileOrDirectory, overwrite = true)
                } else {
                    hiddenFile.copyTo(fileOrDirectory, overwrite = true)
                }
                Log.i(TAG, "Restored backup after deletion failure: ${fileOrDirectory.absolutePath}")
            }.onFailure { t ->
                Log.e(TAG, "Failed to restore backup: ${t.message}", t)
                Sentry.captureException(t)
            }.onSuccess {
                deleteRecursively(hiddenFile)
            }
        }

        deletedOriginal
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
