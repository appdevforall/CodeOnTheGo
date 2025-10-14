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


/**
 * Repository responsible for persisting and retrieving breakpoints
 * for a given project. Breakpoints are serialized/deserialized
 * using Gson and stored in the project editor cache directory.
 */
object BreakpointRepository {
    private const val BREAKPOINT_FILE_NAME = "breakpoints.json"
    private val BREAKPOINT_LIST_TYPE = object : TypeToken<List<BreakpointDefinition>>() {}.type

    private val bpAdapter =
        RuntimeTypeAdapterFactory.of(BreakpointDefinition::class.java, "kind")
            .registerSubtype(PositionalBreakpoint::class.java, "positional")
            .registerSubtype(MethodBreakpoint::class.java, "method")

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(bpAdapter)
        .create()

    /**
     * Returns the cache directory used by the editor for the given project.
     *
     * @param projectLocation Absolute path of the project.
     * @return [File] pointing to the editor cache directory.
     */
    fun getEditorCacheDir(projectLocation: String): File {
      val projectDir = File(projectLocation)
      val projectCacheDir = Environment.getProjectCacheDir(projectDir)
      return File(projectCacheDir, "editor")
    }

    /**
     * Returns the file where breakpoints are stored for the given project.
     *
     * @param projectLocation Absolute path of the project.
     * @return [File] representing the JSON file containing stored breakpoints.
     */
    fun getStoredBreakpointsFile(projectLocation: String): File {
        val editorCacheDir = getEditorCacheDir(projectLocation)

        return File(editorCacheDir, BREAKPOINT_FILE_NAME)
    }

    /**
     * Reads and deserializes the list of breakpoints from disk.
     *
     * @param projectLocation Absolute path of the project.
     * @return List of [BreakpointDefinition] objects, or an empty list if
     *         the file does not exist, is blank, or cannot be parsed.
     */
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

    /**
     * Serializes and saves the given list of breakpoints to disk.
     *
     * Serialization is done on [Dispatchers.Default] (CPU-bound),
     * and writing is performed on [Dispatchers.IO] (I/O-bound).
     *
     * @param projectLocation Absolute path of the project.
     * @param breakpoints List of [BreakpointDefinition] to save.
     */
    suspend fun saveBreakpoints(projectLocation: String, breakpoints: List<BreakpointDefinition>) {
        val file = getStoredBreakpointsFile(projectLocation)

        val jsonOut = withContext(Dispatchers.Default) {
            gson.toJson(breakpoints, BREAKPOINT_LIST_TYPE)
        }

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

    /**
     * Returns only the positional breakpoints stored in the project.
     *
     * @param projectLocation Absolute path of the project.
     * @return List of [PositionalBreakpoint] objects.
     */
    suspend fun getPositionalBreakpoints(projectLocation: String): List<PositionalBreakpoint> {
        val all = getBreakpointsLocalStored(projectLocation)
        return all.filterIsInstance<PositionalBreakpoint>()
    }

}