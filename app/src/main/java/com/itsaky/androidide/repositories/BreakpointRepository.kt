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


typealias StoredBreakpointsType = List<BreakpointDefinition>

private val StoredBreakpointsTypeToken = object : TypeToken<StoredBreakpointsType>() {}

/**
 * Repository responsible for persisting and retrieving breakpoints
 * for a given project. Breakpoints are serialized/deserialized
 * using Gson and stored in the project editor cache directory.
 */
object BreakpointRepository {
	private const val BREAKPOINT_FILE_NAME = "breakpoints.json"

	private val bpAdapter =
		RuntimeTypeAdapterFactory.of(BreakpointDefinition::class.java, "kind")
			.registerSubtype(PositionalBreakpoint::class.java, "positional")
			.registerSubtype(MethodBreakpoint::class.java, "method")

	private val gson: Gson = GsonBuilder()
		.registerTypeAdapterFactory(bpAdapter)
		.create()

	/**
	 * Returns the file where breakpoints are stored for the given project.
	 *
	 * @param projectDir The project directory.
	 * @return [File] representing the JSON file containing stored breakpoints.
	 */
	fun getBreakpointsStorageFile(projectDir: File): File {
		return Environment.getProjectCacheDir(projectDir).resolve("editor/$BREAKPOINT_FILE_NAME")
	}

	/**
	 * Reads and deserializes the list of breakpoints from disk.
	 */
	suspend fun getStoredBreakpoints(projectDir: File): StoredBreakpointsType {
		val breakpointFile = getBreakpointsStorageFile(projectDir)

		return withContext(Dispatchers.IO) {
			if (!breakpointFile.exists()) {
				return@withContext emptyList()
			}

			try {
				val jsonString = breakpointFile.readText()
				if (jsonString.isBlank()) {
					return@withContext emptyList()
				}

				gson.fromJson(
					jsonString,
					StoredBreakpointsTypeToken.type
				) ?: emptyList()
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
	 * @param projectDir The project directory.
	 * @param breakpoints List of [BreakpointDefinition] to save.
	 */
	suspend fun saveBreakpoints(
		projectDir: File,
		breakpoints: StoredBreakpointsType
	) {
		val file = getBreakpointsStorageFile(projectDir)

		val jsonOut = withContext(Dispatchers.Default) {
			gson.toJson(breakpoints, StoredBreakpointsTypeToken.type)
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
}