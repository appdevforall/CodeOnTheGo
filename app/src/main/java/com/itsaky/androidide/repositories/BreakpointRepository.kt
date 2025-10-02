package com.itsaky.androidide.repositories

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.lsp.debug.model.BreakpointDefinition
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.lsp.debug.model.MethodBreakpoint
import com.itsaky.androidide.tooling.api.util.RuntimeTypeAdapterFactory
import com.itsaky.androidide.utils.Environment
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


object BreakpointRepository {
    const val BREAKPOINT_FILE_NAME = "breakpoints.json"

    private val bpAdapter =
        RuntimeTypeAdapterFactory.of(BreakpointDefinition::class.java, "kind")
            .registerSubtype(PositionalBreakpoint::class.java, "positional")
            .registerSubtype(MethodBreakpoint::class.java, "method")

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(bpAdapter)
        .create()

    fun getEditorCacheDir(projectLocation: String): File {
      val projectDir = File(projectLocation)
      val projectCacheDir = Environment.getProjectCacheDir(projectDir)
      return File(projectCacheDir, "editor")
    }

    fun getStoredBreakpointsFile(projectLocation: String): File {
        val editorCacheDir = getEditorCacheDir(projectLocation)

        return File(editorCacheDir, BREAKPOINT_FILE_NAME)
    }

    suspend fun getBreakpointsLocalStored(projectLocation: String): List<BreakpointDefinition> {
        val breakpointFile = getStoredBreakpointsFile(projectLocation)

        return withContext(Dispatchers.IO) {
            if (!breakpointFile.exists()) {
                return@withContext emptyList()
            }

            try {
                val jsonString = breakpointFile.readText()
                if (jsonString.isBlank()) {
                    return@withContext emptyList()
                }

                val type = object : TypeToken<List<BreakpointDefinition>>() {}.type
                gson.fromJson<List<BreakpointDefinition>>(jsonString, type) ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun saveBreakpoints(projectLocation: String, breakpoints: List<BreakpointDefinition>) {
        val file = getStoredBreakpointsFile(projectLocation)
        val type = object : TypeToken<List<BreakpointDefinition>>() {}.type
        val jsonOut = gson.toJson(breakpoints, type)

        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                if (!file.exists()) file.createNewFile()

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

    suspend fun getPositionalBreakpoints(projectLocation: String): List<PositionalBreakpoint> {
        val all = getBreakpointsLocalStored(projectLocation)
        return all.filterIsInstance<PositionalBreakpoint>()
    }

}