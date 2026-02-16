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
import androidx.core.database.sqlite.transaction

/**
 * Manages plugin documentation in an isolated database.
 * This ensures plugin documentation is independent from the main app's documentation
 * and won't be affected by app updates that change the database schema.
 */
class PluginDocumentationManager(private val context: Context) {

    companion object {
        private const val TAG = "PluginDocManager"
    }

    private val databaseVersion = 1
    private val databaseName = "plugin_documentation.db"

    // Database schema creation statements
    private val createCategoriesTable = """
        CREATE TABLE IF NOT EXISTS PluginTooltipCategories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            category TEXT NOT NULL UNIQUE
        )
    """

    private val createTooltipsTable = """
        CREATE TABLE IF NOT EXISTS PluginTooltips (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            categoryId INTEGER NOT NULL,
            tag TEXT NOT NULL,
            summary TEXT NOT NULL,
            detail TEXT,
            FOREIGN KEY(categoryId) REFERENCES PluginTooltipCategories(id),
            UNIQUE(categoryId, tag)
        )
    """

    private val createButtonsTable = """
        CREATE TABLE IF NOT EXISTS PluginTooltipButtons (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tooltipId INTEGER NOT NULL,
            description TEXT NOT NULL,
            uri TEXT NOT NULL,
            buttonNumberId INTEGER NOT NULL,
            FOREIGN KEY(tooltipId) REFERENCES PluginTooltips(id) ON DELETE CASCADE
        )
    """

    private val createTrackingTable = """
        CREATE TABLE IF NOT EXISTS PluginTracking (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            pluginId TEXT NOT NULL,
            tooltipId INTEGER NOT NULL,
            categoryId INTEGER NOT NULL,
            installedAt INTEGER NOT NULL,
            UNIQUE(pluginId, tooltipId)
        )
    """

    private val createMetadataTable = """
        CREATE TABLE IF NOT EXISTS PluginMetadata (
            pluginId TEXT PRIMARY KEY,
            lastUpdated INTEGER NOT NULL,
            version TEXT
        )
    """

    /**
     * Get or create the plugin documentation database.
     */
    private suspend fun getPluginDatabase(): SQLiteDatabase? = withContext(Dispatchers.IO) {
        try {
            val dbPath = context.getDatabasePath(databaseName).absolutePath
            val dbFile = File(dbPath)

            val isNewDatabase = !dbFile.exists()

            // Open or create the database
            val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

            if (isNewDatabase) {
                Log.d(TAG, "Creating new plugin documentation database at: $dbPath")
                initializeDatabase(db)
            } else {
                // Check if tables exist, initialize if not
                if (!tablesExist(db)) {
                    Log.d(TAG, "Database exists but tables missing, initializing...")
                    initializeDatabase(db)
                }
            }

            db
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open/create plugin documentation database", e)
            null
        }
    }

    /**
     * Check if required tables exist in the database.
     */
    private fun tablesExist(db: SQLiteDatabase): Boolean {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN (?, ?, ?, ?, ?)",
            arrayOf("PluginTooltipCategories", "PluginTooltips", "PluginTooltipButtons", "PluginTracking", "PluginMetadata")
        )
        val exists = cursor.moveToFirst() && cursor.getInt(0) == 5
        cursor.close()
        return exists
    }

    /**
     * Initialize the database with required tables.
     */
    private fun initializeDatabase(db: SQLiteDatabase) {
        db.transaction {
            try {
                execSQL(createCategoriesTable)
                execSQL(createTooltipsTable)
                execSQL(createButtonsTable)
                execSQL(createTrackingTable)
                execSQL(createMetadataTable)

                // Create indices for better performance
                execSQL("CREATE INDEX IF NOT EXISTS idx_tooltips_category ON PluginTooltips(categoryId)")
                execSQL("CREATE INDEX IF NOT EXISTS idx_buttons_tooltip ON PluginTooltipButtons(tooltipId)")
                execSQL("CREATE INDEX IF NOT EXISTS idx_tracking_plugin ON PluginTracking(pluginId)")

                Log.d(TAG, "Plugin documentation database initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize plugin documentation database", e)
                throw e
            } finally {
            }
        }
    }

    /**
     * Initialize plugin database if needed.
     * This can be called on app startup to ensure the database exists.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val db = getPluginDatabase()
        db?.close()
        Log.d(TAG, "Plugin documentation system initialized")
    }

    /**
     * Install documentation from a plugin.
     * Inserts tooltips into the isolated plugin documentation database.
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

            // Insert or get category ID
            val categoryId = insertOrGetCategoryId(db, category)

            // Insert tooltips and track them
            for (entry in entries) {
                val tooltipId = insertTooltip(db, categoryId, entry)

                // Track this tooltip for later cleanup
                trackPluginTooltip(db, pluginId, tooltipId, categoryId)

                // Insert buttons for this tooltip
                entry.buttons.sortedBy { it.order }.forEachIndexed { index, button ->
                    insertTooltipButton(db, tooltipId, button.description, button.uri, index)
                }
            }

            // Update plugin metadata
            updatePluginMetadata(db, pluginId)

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

        val db = getPluginDatabase()
        if (db == null) {
            Log.w(TAG, "Cannot remove documentation for $pluginId - database not available")
            return@withContext false
        }

        db.beginTransaction()
        try {
            removePluginDocumentationInternal(db, pluginId)

            // Remove plugin metadata
            db.delete("PluginMetadata", "pluginId = ?", arrayOf(pluginId))

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
            "PluginTracking",
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
                "PluginTooltipButtons",
                "tooltipId IN ($placeholders)",
                tooltipIds.map { it.toString() }.toTypedArray()
            )

            // Delete the tooltips themselves
            db.delete(
                "PluginTooltips",
                "id IN ($placeholders)",
                tooltipIds.map { it.toString() }.toTypedArray()
            )
        }

        // Remove tracking entries
        db.delete("PluginTracking", "pluginId = ?", arrayOf(pluginId))

        // Note: We don't delete categories as they might be shared with other plugins
    }

    private fun insertOrGetCategoryId(db: SQLiteDatabase, category: String): Long {
        // Check if category already exists
        val cursor = db.query(
            "PluginTooltipCategories",
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
        return db.insert("PluginTooltipCategories", null, values)
    }

    private fun insertTooltip(
        db: SQLiteDatabase,
        categoryId: Long,
        entry: PluginTooltipEntry
    ): Long {
        val disclaimer = context.getString(R.string.plugin_documentation_third_party_disclaimer)
        // Check if tooltip with same tag already exists in this category
        val existingCursor = db.query(
            "PluginTooltips",
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
                put("summary", entry.summary + disclaimer)
                put("detail", if (entry.detail.isNotBlank()) entry.detail + disclaimer else "")
            }
            db.update("PluginTooltips", updateValues, "id = ?", arrayOf(existingId.toString()))

            // Delete old buttons (we'll re-insert them)
            db.delete("PluginTooltipButtons", "tooltipId = ?", arrayOf(existingId.toString()))

            return existingId
        }
        existingCursor.close()

        // Insert new tooltip with disclaimer
        val values = ContentValues().apply {
            put("categoryId", categoryId)
            put("tag", entry.tag)
            put("summary", entry.summary + disclaimer)
            put("detail", if (entry.detail.isNotBlank()) entry.detail + disclaimer else "")
        }
        return db.insert("PluginTooltips", null, values)
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
            put("installedAt", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            "PluginTracking",
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
        db.insert("PluginTooltipButtons", null, values)
    }

    private fun updatePluginMetadata(db: SQLiteDatabase, pluginId: String) {
        val values = ContentValues().apply {
            put("pluginId", pluginId)
            put("lastUpdated", System.currentTimeMillis())
            put("version", "1.0") // Can be updated to track actual plugin version
        }
        db.insertWithOnConflict(
            "PluginMetadata",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Get all plugin categories currently in the database.
     */
    suspend fun getPluginCategories(pluginId: String): List<String> = withContext(Dispatchers.IO) {
        val db = getPluginDatabase() ?: return@withContext emptyList()

        val categories = mutableListOf<String>()
        try {
            val cursor = db.rawQuery("""
                SELECT DISTINCT TC.category
                FROM PluginTooltipCategories TC
                INNER JOIN PluginTracking PTT ON TC.id = PTT.categoryId
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
     * Check if the plugin documentation database exists and is accessible.
     */
    suspend fun isDatabaseAvailable(): Boolean = withContext(Dispatchers.IO) {
        val dbPath = context.getDatabasePath(databaseName).absolutePath
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
        val db = getPluginDatabase() ?: return@withContext false

        try {
            val cursor = db.query(
                "PluginTracking",
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
            Log.d(TAG, "Documentation database does not exist, initializing for $pluginId...")
            initialize()
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
        if (plugins.isEmpty()) {
            return@withContext 0
        }

        if (!isDatabaseAvailable()) {
            Log.d(TAG, "Documentation database does not exist, initializing...")
            initialize()
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