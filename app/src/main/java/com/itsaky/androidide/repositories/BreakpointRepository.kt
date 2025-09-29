package com.itsaky.androidide.repositories

import com.google.common.collect.HashBasedTable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.lsp.debug.model.BreakpointDefinition
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import java.io.File

object BreakpointRepository {

    private val gson = Gson()

    private var breakpointsFile: File? = null
    private const val BREAKPOINT_FILE_NAME = "breakpoints.json"

    fun getBreakpointFile(projectLocation: String): File? {
        val projectDir = File(projectLocation)
        val cacheDir = File(projectDir, ".androidide/editor")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val file = File(cacheDir, BREAKPOINT_FILE_NAME)
        if (!file.exists()) {
            file.createNewFile()
        }

        breakpointsFile = file

        return breakpointsFile
    }

    fun loadBreakpoints(projectLocation: String): List<BreakpointDefinition> {
        val breakpointFile = getBreakpointFile(projectLocation)

        if (breakpointFile == null || !breakpointFile.exists()) {
            return emptyList()
        }

        return try {
            val jsonString = breakpointFile.readText()
            if (jsonString.isBlank()) {
                return emptyList()
            }

            val type = object : TypeToken<List<PositionalBreakpoint>>() {}.type
            gson.fromJson(jsonString, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveBreakpoints(table: HashBasedTable<String, Int, PositionalBreakpoint>) {
        val file = breakpointsFile ?: return

        try {
            val breakpointsToSave = table.values().toList()

            val jsonOut = gson.toJson(breakpointsToSave)

            file.writeText(jsonOut)

        } catch (_: Exception) {}
    }

    fun addBreakpoint(table: HashBasedTable<String, Int, PositionalBreakpoint>) {
        saveBreakpoints(table)
    }

    fun removeBreakpoint(table: HashBasedTable<String, Int, PositionalBreakpoint>) {
        saveBreakpoints(table)
    }
}