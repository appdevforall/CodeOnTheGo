package com.itsaky.androidide.plugins.manager.documentation

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.manager.pluginCategory
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Manages plugin documentation by writing into the main documentation.db.
 * Plugin entries are stored in the existing Tooltips/TooltipCategories/TooltipButtons tables,
 * differentiated by a "plugin_<pluginId>" category prefix so they never conflict with
 * built-in documentation.
 */
class PluginDocumentationManager(private val context: Context) {

    companion object {
        private const val TAG = "PluginDocManager"
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
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to open documentation.db for plugin writes", e)
            null
        }
    }


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
                    val resolvedUri = resolvePluginButtonUri(pluginId, button.uri, button.directPath)
                    insertTooltipButton(db, tooltipId, button.description, resolvedUri, index)
                }
            }

            db.setTransactionSuccessful()
            Log.d(TAG, "Successfully installed documentation for plugin $pluginId")
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to remove documentation for plugin $pluginId", e)
            false
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /**
     * Install Tier 3 documentation (full help pages) contributed by a plugin.
     *
     * Walks the plugin-declared asset subdirectory, compresses each file per
     * the existing ContentTypes.compression column, chunks blobs at 1 MB to
     * match WebServer's read loop, and inserts everything under the reserved
     * path namespace "plugin/<pluginId>/..." inside a single transaction.
     */
    suspend fun installPluginTier3Documentation(
        pluginId: String,
        plugin: DocumentationExtension,
        pluginApkPath: String
    ): Boolean = withContext(Dispatchers.IO) {

        val assetPath = plugin.getTier3DocsAssetPath()
        if (assetPath.isNullOrBlank()) {
            return@withContext true
        }

        val pluginAssets = try {
            openPluginOnlyAssets(pluginApkPath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to open plugin APK assets for $pluginId", e)
            return@withContext false
        }

        val db = getPluginDatabase()
        if (db == null) {
            Log.w(TAG, "Cannot install Tier 3 docs for $pluginId - database not available")
            pluginAssets.close()
            return@withContext false
        }

        val resolver = ExtensionToContentTypeResolver()
        var inserted = 0
        var skipped = 0

        db.beginTransaction()
        try {
            removePluginTier3Internal(db, pluginId)

            for (asset in Tier3AssetWalker.walk(pluginAssets, assetPath)) {
                val ext = asset.relativePath.substringAfterLast('.', "")
                if (ext.isEmpty()) {
                    Log.w(TAG, "Skipping Tier 3 asset without extension: ${asset.relativePath}")
                    skipped++
                    continue
                }
                val row = resolver.resolve(db, ext)
                if (row == null) {
                    Log.w(TAG, "No ContentType for .$ext (${asset.relativePath}); skipping")
                    skipped++
                    continue
                }

                val payload = if (row.compression == "brotli") {
                    BrotliCompressor.compress(asset.bytes)
                } else {
                    asset.bytes
                }

                val safeRelative = try {
                    normalizeLocalDocumentationPath(asset.relativePath)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Skipping Tier 3 asset with invalid path '${asset.relativePath}': ${e.message}")
                    skipped++
                    continue
                }
                val basePath = "plugin/$pluginId/$safeRelative"
                insertContentChunked(db, basePath, payload, row.id)
                inserted++
            }

            db.setTransactionSuccessful()
            Log.d(TAG, "Installed $inserted Tier 3 documents for plugin $pluginId (skipped=$skipped)")
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to install Tier 3 docs for plugin $pluginId", e)
            false
        } finally {
            db.endTransaction()
            db.close()
            pluginAssets.close()
        }
    }

    /**
     * Remove all Tier 3 documentation rows owned by the given plugin.
     */
    suspend fun removePluginTier3Documentation(
        pluginId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val db = getPluginDatabase() ?: return@withContext false
        db.beginTransaction()
        try {
            val deleted = removePluginTier3Internal(db, pluginId)
            db.setTransactionSuccessful()
            Log.d(TAG, "Removed $deleted Tier 3 rows for plugin $pluginId")
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to remove Tier 3 docs for plugin $pluginId", e)
            false
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /**
     * Verify that Tier 3 content exists for this plugin; reinstall if missing.
     * Mirrors [verifyAndRecreateDocumentation] for the Tier 1/2 pipeline.
     */
    suspend fun verifyAndRecreateTier3Documentation(
        pluginId: String,
        plugin: DocumentationExtension,
        pluginApkPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (plugin.getTier3DocsAssetPath().isNullOrBlank()) {
            return@withContext true
        }
        if (!isDatabaseAvailable()) {
            Log.d(TAG, "documentation.db not available yet for Tier 3 verify of $pluginId")
            return@withContext false
        }
        if (isPluginTier3DocumentationInstalled(pluginId)) {
            Log.d(TAG, "Tier 3 docs already present for $pluginId")
            return@withContext true
        }
        Log.d(TAG, "Tier 3 docs missing for $pluginId, installing...")
        installPluginTier3Documentation(pluginId, plugin, pluginApkPath)
    }

    /**
     * Check if any Tier 3 content rows exist for this plugin.
     */
    suspend fun isPluginTier3DocumentationInstalled(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val db = getPluginDatabase() ?: return@withContext false
        try {
            val prefix = "plugin/$pluginId"
            db.rawQuery(
                "SELECT 1 FROM Content WHERE path = ? OR path LIKE ? ESCAPE '\\' LIMIT 1",
                arrayOf(prefix, "${escapeLike(prefix)}/%")
            ).use { cursor -> cursor.moveToFirst() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to probe Tier 3 installation for $pluginId", e)
            false
        } finally {
            db.close()
        }
    }

    /**
     * Build an AssetManager that sees ONLY the plugin APK, so walking a top-level
     * asset directory cannot pick up collisions with the host app's assets.
     */
    private fun openPluginOnlyAssets(pluginApkPath: String): AssetManager {
        @Suppress("DEPRECATION")
        val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(am, pluginApkPath) as? Int ?: 0
        if (cookie == 0) {
            throw IllegalStateException("addAssetPath returned 0 for $pluginApkPath")
        }
        return am
    }

    private fun removePluginTier3Internal(db: SQLiteDatabase, pluginId: String): Int {
        val prefix = "plugin/$pluginId"
        return db.delete(
            "Content",
            "path = ? OR path LIKE ? ESCAPE '\\'",
            arrayOf(prefix, "${escapeLike(prefix)}/%")
        )
    }

    private fun insertContentChunked(
        db: SQLiteDatabase,
        basePath: String,
        payload: ByteArray,
        contentTypeId: Long
    ) {
        val chunkSize = 1024 * 1024
        if (payload.size < chunkSize) {
            insertContentRow(db, basePath, payload, contentTypeId)
            return
        }

        var offset = 0
        var fragment = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            val slice = payload.copyOfRange(offset, end)
            val path = if (fragment == 0) basePath else "$basePath-$fragment"
            insertContentRow(db, path, slice, contentTypeId)
            offset = end
            fragment++
        }
        if (payload.size % chunkSize == 0) {
            insertContentRow(db, "$basePath-$fragment", ByteArray(0), contentTypeId)
        }
    }

    private fun insertContentRow(
        db: SQLiteDatabase,
        path: String,
        blob: ByteArray,
        contentTypeId: Long
    ) {
        val values = ContentValues().apply {
            put("path", path)
            put("content", blob)
            put("contentTypeID", contentTypeId)
            put("languageId", 1)
        }
        db.insertOrThrow("Content", null, values)
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

    private fun escapeLike(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun normalizeLocalDocumentationPath(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) {
            "Documentation paths must not contain '..' segments: $path"
        }
        return segments.joinToString("/")
    }

    private fun resolvePluginButtonUri(
        pluginId: String,
        rawUri: String,
        directPath: Boolean
    ): String {
        if (rawUri.isEmpty()) return rawUri
        if (rawUri.contains("://")) return rawUri
        val absolute = directPath || rawUri.startsWith("/")
        val normalized = normalizeLocalDocumentationPath(rawUri.trimStart('/'))
        return if (absolute) normalized else "plugin/$pluginId/$normalized"
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
            if (e is CancellationException) throw e
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
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to verify/recreate documentation for $pluginId", e)
            }
        }

        if (recreatedCount > 0) {
            Log.i(TAG, "Recreated documentation for $recreatedCount plugins")
        }

        recreatedCount
    }
}
