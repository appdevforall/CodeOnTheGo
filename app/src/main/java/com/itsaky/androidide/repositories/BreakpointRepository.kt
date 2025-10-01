package com.itsaky.androidide.repositories

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.lsp.debug.model.BreakpointDefinition
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.utils.Environment
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


object BreakpointRepository {
    private const val BREAKPOINT_FILE_NAME = "breakpoints.json"

    private val gson = Gson()

    fun getBreakpointFile(projectLocation: String): File {
        val projectDir = File(projectLocation)
        val projectCacheDir = Environment.getProjectCacheDir(projectDir)
        val cacheDir = File(projectDir, ".androidide/editor")
        val editorCacheDir = File(projectCacheDir, "editor")
        println(cacheDir)
        return File(editorCacheDir, BREAKPOINT_FILE_NAME)
    }

    suspend fun loadBreakpoints(projectLocation: String): List<PositionalBreakpoint> {
        val breakpointFile = getBreakpointFile(projectLocation)

        return withContext(Dispatchers.IO) {
            if (!breakpointFile.exists()) {
                return@withContext emptyList()
            }

            try {
                val jsonString = breakpointFile.readText()
                if (jsonString.isBlank()) {
                    return@withContext emptyList()
                }

                val type = object : TypeToken<List<PositionalBreakpoint>>() {}.type
                gson.fromJson(jsonString, type) ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun saveBreakpoints(projectLocation: String, breakpoints: List<BreakpointDefinition>) {
        val file = getBreakpointFile(projectLocation)
        val jsonOut = gson.toJson(breakpoints)

        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()

                if (!file.exists()) {
                    file.createNewFile()
                }

                file.writeText(jsonOut)
            } catch (e: Exception) {
                Log.e(
                    "BreakpointManager",
                    "Failed to save breakpoints to file: ${file.absolutePath}",
                    e
                )
                Sentry.captureException(e)
            }
        }
    }
}