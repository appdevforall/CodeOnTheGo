package com.itsaky.androidide.plugins.manager.documentation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages plugin documentation by writing into the main documentation.db.
 * Plugin entries are stored in the existing Tooltips/TooltipCategories/TooltipButtons tables,
 * differentiated by a "plugin_<pluginId>" category prefix so they never conflict with
 * built-in documentation.
 */
class PluginDocumentationManager(private val context: Context) {

    companion object {
        private const val TAG = "PluginDocManager"
        private const val PLUGIN_CATEGORY_PREFIX = "plugin_"
    }

    private val databaseName = "documentation.db"

    private suspend fun getPluginDatabase(): SQLiteDatabase? = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(databaseName)
            if (!dbFile.exists()) {
                Log.w(TAG, "documentation.db not yet available at: ${dbFile.absolutePath}")
                return@withContext null
            }
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open documentation.db for plugin writes", e)
            null
        }
    }

    private fun pluginCategory(pluginId: String) = "$PLUGIN_CATEGORY_PREFIX$pluginId"

    /**
     * Initialize plugin documentation system.
     * Also cleans up the legacy plugin_documentation.db if present.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val legacyDb = context.getDatabasePath("plugin_documentation.db")
        if (legacyDb.exists()) {
            if (legacyDb.delete()) {
                Log.d(TAG, "Removed legacy plugin_documentation.db")
            } else {
                Log.w(TAG, "Failed to remove legacy plugin_documentation.db")
            }
        }
        Log.d(TAG, "Plugin documentation system initialized")
    }

    /**
     * Install documentation from a plugin into documentation.db.
     */
    suspend fun installPluginDocumentation(
        pluginId: String,
        plugin: DocumentationExtension
    ): Boolean = withContext(Dispatchers.IO) {

        if (!plugin.onDocumentationInstall()) {
            Log.d(TAG, "Plugin $pluginId declined documentation installation")
            return@withContext false
        }

        val db = getPluginDatabase()
        if (db == null) {
            Log.w(TAG, "Cannot install documentation for $pluginId - database not available")
            return@withContext false
        }

        val entries = plugin.getTooltipEntries()

        if (entries.isEmpty()) {
            Log.d(TAG, "Plugin $pluginId has no tooltip entries")
            db.close()
            return@withContext true
        }

        Log.d(TAG, "Installing ${entries.size} tooltip entries for plugin $pluginId")

        db.beginTransaction()
        try {
            removePluginDocumentationInternal(db, pluginId)

            val categoryId = insertOrGetCategoryId(db, pluginCategory(pluginId))

            for (entry in entries) {
                val tooltipId = insertTooltip(db, categoryId, entry)
                entry.buttons.sortedBy { it.order }.forEachIndexed { index, button ->
                    insertTooltipButton(db, tooltipId, button.description, button.uri, index)
                }
            }

            db.setTransactionSuccessful()
            Log.d(TAG, "Successfully installed documentation for plugin $pluginId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install documentation for plugin $pluginId", e)
            false
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /**
     * Remove all documentation for a plugin from documentation.db.
     */
    suspend fun removePluginDocumentation(
        pluginId: String,
        plugin: DocumentationExtension? = null
    ): Boolean = withContext(Dispatchers.IO) {

        plugin?.onDocumentationUninstall()

        val db = getPluginDatabase()
        if (db == null) {
            Log.w(TAG, "Cannot remove documentation for $pluginId - database not available")
            return@withContext false
        }

        db.beginTransaction()
        try {
            removePluginDocumentationInternal(db, pluginId)
            db.setTransactionSuccessful()
            Log.d(TAG, "Successfully removed documentation for plugin $pluginId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove documentation for plugin $pluginId", e)
            false
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun removePluginDocumentationInternal(db: SQLiteDatabase, pluginId: String) {
        val category = pluginCategory(pluginId)

        val cursor = db.rawQuery(
            """
            SELECT T.id FROM Tooltips AS T
            INNER JOIN TooltipCategories AS TC ON T.categoryId = TC.id
            WHERE TC.category = ?
            """.trimIndent(),
            arrayOf(category)
        )

        val tooltipIds = mutableListOf<Long>()
        while (cursor.moveToNext()) {
            tooltipIds.add(cursor.getLong(0))
        }
        cursor.close()

        if (tooltipIds.isNotEmpty()) {
            val placeholders = tooltipIds.joinToString(",") { "?" }
            val args = tooltipIds.map { it.toString() }.toTypedArray()
            db.delete("TooltipButtons", "tooltipId IN ($placeholders)", args)
            db.delete("Tooltips", "id IN ($placeholders)", args)
        }

        db.delete("TooltipCategories", "category = ?", arrayOf(category))
    }

    private fun insertOrGetCategoryId(db: SQLiteDatabase, category: String): Long {
        val cursor = db.query(
            "TooltipCategories",
            arrayOf("id"),
            "category = ?",
            arrayOf(category),
            null, null, null
        )

        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            cursor.close()
            return id
        }
        cursor.close()

        val values = ContentValues().apply {
            put("category", category)
        }
        return db.insert("TooltipCategories", null, values)
    }

    private fun insertTooltip(
        db: SQLiteDatabase,
        categoryId: Long,
        entry: PluginTooltipEntry
    ): Long {
        val disclaimer = context.getString(R.string.plugin_documentation_third_party_disclaimer)

        val existingCursor = db.query(
            "Tooltips",
            arrayOf("id"),
            "categoryId = ? AND tag = ?",
            arrayOf(categoryId.toString(), entry.tag),
            null, null, null
        )

        if (existingCursor.moveToFirst()) {
            val existingId = existingCursor.getLong(0)
            existingCursor.close()

            val updateValues = ContentValues().apply {
                put("summary", entry.summary + disclaimer)
                put("detail", if (entry.detail.isNotBlank()) entry.detail + disclaimer else "")
            }
            db.update("Tooltips", updateValues, "id = ?", arrayOf(existingId.toString()))
            db.delete("TooltipButtons", "tooltipId = ?", arrayOf(existingId.toString()))
            return existingId
        }
        existingCursor.close()

        val values = ContentValues().apply {
            put("categoryId", categoryId)
            put("tag", entry.tag)
            put("summary", entry.summary + disclaimer)
            put("detail", if (entry.detail.isNotBlank()) entry.detail + disclaimer else "")
        }
        return db.insert("Tooltips", null, values)
    }

    private fun insertTooltipButton(
        db: SQLiteDatabase,
        tooltipId: Long,
        description: String,
        uri: String,
        order: Int
    ) {
        val values = ContentValues().apply {
            put("tooltipId", tooltipId)
            put("description", description)
            put("uri", uri)
            put("buttonNumberId", order)
        }
        db.insert("TooltipButtons", null, values)
    }

    /**
     * Check if the plugin documentation database is accessible.
     */
    suspend fun isDatabaseAvailable(): Boolean = withContext(Dispatchers.IO) {
        context.getDatabasePath(databaseName).exists()
    }

    /**
     * Check if documentation for a specific plugin exists in documentation.db.
     */
    suspend fun isPluginDocumentationInstalled(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val db = getPluginDatabase() ?: return@withContext false

        try {
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM TooltipCategories WHERE category = ?",
                arrayOf(pluginCategory(pluginId))
            )
            val installed = cursor.moveToFirst() && cursor.getInt(0) > 0
            cursor.close()
            installed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check plugin documentation for $pluginId", e)
            false
        } finally {
            db.close()
        }
    }

    /**
     * Verify and recreate plugin documentation if missing.
     */
    suspend fun verifyAndRecreateDocumentation(
        pluginId: String,
        plugin: DocumentationExtension
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isDatabaseAvailable()) {
            Log.d(TAG, "documentation.db not available yet for $pluginId, skipping")
            return@withContext false
        }

        if (!isPluginDocumentationInstalled(pluginId)) {
            Log.d(TAG, "Plugin documentation missing for $pluginId, recreating...")
            return@withContext installPluginDocumentation(pluginId, plugin)
        }

        Log.d(TAG, "Plugin documentation already exists for $pluginId")
        true
    }

    /**
     * Verify and recreate documentation for all plugins that support it.
     */
    suspend fun verifyAllPluginDocumentation(
        plugins: Map<String, DocumentationExtension>
    ): Int = withContext(Dispatchers.IO) {
        if (plugins.isEmpty()) return@withContext 0

        if (!isDatabaseAvailable()) {
            Log.d(TAG, "documentation.db not available yet, skipping verification")
            return@withContext 0
        }

        var recreatedCount = 0

        for ((pluginId, plugin) in plugins) {
            try {
                if (!isPluginDocumentationInstalled(pluginId)) {
                    Log.d(TAG, "Recreating missing documentation for plugin: $pluginId")
                    if (installPluginDocumentation(pluginId, plugin)) {
                        recreatedCount++
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify/recreate documentation for $pluginId", e)
            }
        }

        if (recreatedCount > 0) {
            Log.i(TAG, "Recreated documentation for $recreatedCount plugins")
        }

        recreatedCount
    }
}
