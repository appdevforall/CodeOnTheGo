package com.itsaky.androidide.plugins.manager.documentation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages plugin documentation integration with the IDE's tooltip system.
 * Inserts plugin documentation directly into the main documentation database.
 */
class PluginDocumentationManager(private val context: Context) {

    companion object {
        private const val TAG = "PluginDocManager"

        private const val THIRD_PARTY_DISCLAIMER = "<br><br><em style='color: #888; font-size: 0.9em;'>⚠️ This documentation is provided by a third-party plugin and is not part of the official COGO IDE documentation.</em>"

        private const val CREATE_PLUGIN_TRACKING_TABLE = """
            CREATE TABLE IF NOT EXISTS PluginTooltipTracking (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pluginId TEXT NOT NULL,
                tooltipId INTEGER NOT NULL,
                categoryId INTEGER NOT NULL,
                UNIQUE(pluginId, tooltipId)
            )
        """
    }

    private suspend fun getDocumentationDatabase(): SQLiteDatabase? = withContext(Dispatchers.IO) {
        try {
            val dbPath = Environment.DOC_DB.absolutePath
            if (!File(dbPath).exists()) {
                Log.w(TAG, "Documentation database does not exist at: $dbPath")
                return@withContext null
            }
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open documentation database", e)
            null
        }
    }

    /**
     * Initialize plugin tracking table if it doesn't exist.
     */
    suspend fun initializePluginTracking() = withContext(Dispatchers.IO) {
        val db = getDocumentationDatabase() ?: return@withContext

        try {
            db.execSQL(CREATE_PLUGIN_TRACKING_TABLE)
            Log.d(TAG, "Plugin documentation tracking table initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize plugin tracking table", e)
        } finally {
            db.close()
        }
    }

    /**
     * Install documentation from a plugin that implements DocumentationExtension.
     * Inserts tooltips directly into the main Tooltips, TooltipCategories, and TooltipButtons tables.
     */
    suspend fun installPluginDocumentation(
        pluginId: String,
        plugin: DocumentationExtension
    ): Boolean = withContext(Dispatchers.IO) {

        if (!plugin.onDocumentationInstall()) {
            Log.d(TAG, "Plugin $pluginId declined documentation installation")
            return@withContext false
        }

        val db = getDocumentationDatabase()
        if (db == null) {
            Log.w(TAG, "Cannot install documentation for $pluginId - database not available")
            return@withContext false
        }

        val category = plugin.getTooltipCategory()
        val entries = plugin.getTooltipEntries()

        if (entries.isEmpty()) {
            Log.d(TAG, "Plugin $pluginId has no tooltip entries")
            db.close()
            return@withContext true
        }

        Log.d(TAG, "Installing ${entries.size} tooltip entries for plugin $pluginId (category: $category)")

        db.beginTransaction()
        try {
            // First, remove any existing documentation for this plugin
            removePluginDocumentationInternal(db, pluginId)

            // Insert or get category ID from main TooltipCategories table
            val categoryId = insertOrGetCategoryId(db, category)

            // Insert tooltips into main tables and track them
            for (entry in entries) {
                val tooltipId = insertTooltip(db, categoryId, entry)

                // Track this tooltip for later cleanup
                trackPluginTooltip(db, pluginId, tooltipId, categoryId)

                // Insert buttons for this tooltip
                entry.buttons.sortedBy { it.order }.forEachIndexed { index, button ->
                    insertTooltipButton(db, tooltipId, button.description, button.uri, index)
                }
            }

            // Update LastChange to reflect plugin documentation addition
            updateLastChange(db, "Plugin: $pluginId")

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
     * Remove all documentation for a plugin.
     */
    suspend fun removePluginDocumentation(
        pluginId: String,
        plugin: DocumentationExtension? = null
    ): Boolean = withContext(Dispatchers.IO) {

        plugin?.onDocumentationUninstall()

        val db = getDocumentationDatabase()
        if (db == null) {
            Log.w(TAG, "Cannot remove documentation for $pluginId - database not available")
            return@withContext false
        }

        db.beginTransaction()
        try {
            removePluginDocumentationInternal(db, pluginId)

            // Update LastChange to reflect plugin documentation removal
            updateLastChange(db, "Removed plugin: $pluginId")

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
        // Get all tooltip IDs for this plugin
        val cursor = db.query(
            "PluginTooltipTracking",
            arrayOf("tooltipId"),
            "pluginId = ?",
            arrayOf(pluginId),
            null, null, null
        )

        val tooltipIds = mutableListOf<Long>()
        while (cursor.moveToNext()) {
            tooltipIds.add(cursor.getLong(0))
        }
        cursor.close()

        if (tooltipIds.isNotEmpty()) {
            // Delete buttons for these tooltips
            val placeholders = tooltipIds.joinToString(",") { "?" }
            db.delete(
                "TooltipButtons",
                "tooltipId IN ($placeholders)",
                tooltipIds.map { it.toString() }.toTypedArray()
            )

            // Delete the tooltips themselves
            db.delete(
                "Tooltips",
                "id IN ($placeholders)",
                tooltipIds.map { it.toString() }.toTypedArray()
            )
        }

        // Remove tracking entries
        db.delete("PluginTooltipTracking", "pluginId = ?", arrayOf(pluginId))

        // Note: We don't delete categories as they might be shared with other plugins
    }

    private fun insertOrGetCategoryId(db: SQLiteDatabase, category: String): Long {
        // Check if category already exists
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

        // Insert new category
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
        // Check if tooltip with same tag already exists in this category
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

            // Update existing tooltip with disclaimer
            val updateValues = ContentValues().apply {
                put("summary", entry.summary + THIRD_PARTY_DISCLAIMER)
                put("detail", if (entry.detail.isNotBlank()) entry.detail + THIRD_PARTY_DISCLAIMER else "")
            }
            db.update("Tooltips", updateValues, "id = ?", arrayOf(existingId.toString()))

            // Delete old buttons (we'll re-insert them)
            db.delete("TooltipButtons", "tooltipId = ?", arrayOf(existingId.toString()))

            return existingId
        }
        existingCursor.close()

        // Insert new tooltip into main Tooltips table with disclaimer
        val values = ContentValues().apply {
            put("categoryId", categoryId)
            put("tag", entry.tag)
            put("summary", entry.summary + THIRD_PARTY_DISCLAIMER)
            put("detail", if (entry.detail.isNotBlank()) entry.detail + THIRD_PARTY_DISCLAIMER else "")
        }
        return db.insert("Tooltips", null, values)
    }

    private fun trackPluginTooltip(
        db: SQLiteDatabase,
        pluginId: String,
        tooltipId: Long,
        categoryId: Long
    ) {
        val values = ContentValues().apply {
            put("pluginId", pluginId)
            put("tooltipId", tooltipId)
            put("categoryId", categoryId)
        }
        db.insertWithOnConflict(
            "PluginTooltipTracking",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
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

    private fun updateLastChange(db: SQLiteDatabase, who: String) {
        try {
            // Update the LastChange table to reflect plugin changes
            val values = ContentValues().apply {
                put("now", System.currentTimeMillis())
                put("who", who)
            }

            // Delete existing row and insert new one (assuming single row)
            db.delete("LastChange", null, null)
            db.insert("LastChange", null, values)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update LastChange table", e)
        }
    }

    /**
     * Get all plugin categories currently in the database.
     */
    suspend fun getPluginCategories(pluginId: String): List<String> = withContext(Dispatchers.IO) {
        val db = getDocumentationDatabase() ?: return@withContext emptyList()

        val categories = mutableListOf<String>()
        try {
            val cursor = db.rawQuery("""
                SELECT DISTINCT TC.category
                FROM TooltipCategories TC
                INNER JOIN PluginTooltipTracking PTT ON TC.id = PTT.categoryId
                WHERE PTT.pluginId = ?
            """, arrayOf(pluginId))

            while (cursor.moveToNext()) {
                categories.add(cursor.getString(0))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get plugin categories", e)
        } finally {
            db.close()
        }

        categories
    }

    /**
     * Check if the documentation database exists and is accessible.
     */
    suspend fun isDatabaseAvailable(): Boolean = withContext(Dispatchers.IO) {
        val dbPath = Environment.DOC_DB.absolutePath
        if (!File(dbPath).exists()) {
            return@withContext false
        }

        try {
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            db.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Database exists but cannot be opened", e)
            false
        }
    }

    /**
     * Check if documentation for a specific plugin exists in the database.
     */
    suspend fun isPluginDocumentationInstalled(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val db = getDocumentationDatabase() ?: return@withContext false

        try {
            val cursor = db.query(
                "PluginTooltipTracking",
                arrayOf("COUNT(*) as count"),
                "pluginId = ?",
                arrayOf(pluginId),
                null, null, null
            )

            val hasDocumentation = if (cursor.moveToFirst()) {
                cursor.getInt(0) > 0
            } else {
                false
            }
            cursor.close()
            hasDocumentation
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check plugin documentation for $pluginId", e)
            false
        } finally {
            db.close()
        }
    }

    /**
     * Verify and recreate plugin documentation if missing.
     * This should be called when a plugin is loaded to ensure its documentation is available.
     */
    suspend fun verifyAndRecreateDocumentation(
        pluginId: String,
        plugin: DocumentationExtension
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isDatabaseAvailable()) {
            Log.w(TAG, "Documentation database not available, skipping verification for $pluginId")
            return@withContext false
        }

        val isInstalled = isPluginDocumentationInstalled(pluginId)

        if (!isInstalled) {
            Log.d(TAG, "Plugin documentation missing for $pluginId, recreating...")
            return@withContext installPluginDocumentation(pluginId, plugin)
        }

        Log.d(TAG, "Plugin documentation already exists for $pluginId")
        true
    }

    /**
     * Verify and recreate documentation for all plugins that support it.
     * This can be called after database updates or on app startup.
     */
    suspend fun verifyAllPluginDocumentation(
        plugins: Map<String, DocumentationExtension>
    ): Int = withContext(Dispatchers.IO) {
        if (!isDatabaseAvailable()) {
            Log.w(TAG, "Documentation database not available, skipping verification")
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