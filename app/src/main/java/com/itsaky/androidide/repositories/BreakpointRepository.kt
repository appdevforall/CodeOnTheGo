package com.itsaky.androidide.repositories

import com.google.common.collect.HashBasedTable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.lsp.debug.model.PositionalBreakpoint
import com.itsaky.androidide.roomData.breakpoint.Breakpoint
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

    fun loadBreakpoints(projectLocation: String): MutableList<Breakpoint> {
        val breakpointFile = getBreakpointFile(projectLocation)
        if (breakpointFile != null && !breakpointFile.exists()) {
            return mutableListOf()
        }
        return try {
            val jsonString = breakpointFile?.readText()
            val type = object : TypeToken<MutableList<Breakpoint>>() {}.type
            gson.fromJson(jsonString, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveBreakpoints(table: HashBasedTable<String, Int, PositionalBreakpoint>) {
        val file = breakpointsFile ?: return // si no está inicializado, no hay dónde guardar

        try {
            // 1) Cargar contenido existente (lista de PositionalBreakpoint) si lo hubiera
            val existingList: MutableList<PositionalBreakpoint> = try {
                val raw = file.readText()
                if (raw.isBlank()) mutableListOf()
                else {
                    val type = object : TypeToken<MutableList<PositionalBreakpoint>>() {}.type
                    gson.fromJson<MutableList<PositionalBreakpoint>>(raw, type) ?: mutableListOf()
                }
            } catch (_: Exception) {
                mutableListOf()
            }

            // 2) Indexar existentes por clave única path:line
            fun keyOf(p: PositionalBreakpoint): String {
                val path = p.source?.path ?: ""
                val line = p.line
                return "$path:$line"
            }

            val index = LinkedHashMap<String, PositionalBreakpoint>(existingList.size)
            existingList.forEach { p -> index[keyOf(p)] = p }

            // 3) Insertar nuevos solo si no existen
            for (cell in table.cellSet()) {
                val p = cell.value ?: continue
                val key = keyOf(p)
                if (!index.containsKey(key)) {
                    index[key] = p
                }
            }

            // 4) Serializar y guardar
            val merged = index.values.toList()
            val jsonOut = gson.toJson(merged)
            file.writeText(jsonOut)

        } catch (_: Exception) {
            // Manejar/loguear si deseas
        }
    }
}