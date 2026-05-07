package com.codeonthego.gisplugin.region

import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk view of `/sdcard/CodeOnTheGo/maps/<region-id>/`.
 *
 * Layout per ADFA-2436 §5.2:
 *  - `tiles.mbtiles`   — vector / raster tile bundle (zoom 6–14 by default)
 *  - `pois.json`       — array of POI summaries (~1–5 MB per region)
 *  - `meta.json`       — `{regionId, displayName, bbox, zoomMin, zoomMax,
 *                          source, sizeBytes, downloadedAt, lastUsedAt}`
 *
 * C1 only implements **read** — listing what's on disk so the bottom-sheet tab
 * can populate. Write-side (download into the cache, delete a region) lands in
 * C2 / C3 once the wizard is wired up.
 *
 * The cache lives outside the plugin so it survives plugin re-installs and is
 * shareable between projects. We use the public external storage path because:
 *   (a) Internet-in-a-Box and similar distribution channels can sideload tile
 *       packs into a known path without going through Android's app-specific
 *       storage namespace;
 *   (b) the cache can be 100s of MB and shouldn't count against any single
 *       app's storage quota.
 *
 * Permission caveat: scoped storage on API 30+ disallows arbitrary writes to
 * `/sdcard/...`. The IDE itself runs with broad storage access (via
 * `MANAGE_EXTERNAL_STORAGE`) and this plugin is hosted inside that process,
 * so writes work today. C2 should add a fallback to
 * `Context.getExternalFilesDir("maps")` in case the IDE's permission model
 * tightens.
 *
 * **regionId precondition.** The id must match [REGION_ID_PATTERN] —
 * lowercase alphanumeric + hyphens, must start with alphanumeric, no `.`,
 * `..`, slashes or other path components. [isValidRegionId] is the
 * authoritative validator and is called from every entry point that
 * resolves a path under the cache root.
 */
internal object RegionCache {

    private const val TAG = "GisPlugin.RegionCache"
    private const val DEFAULT_ROOT_NAME = "CodeOnTheGo/maps"

    /**
     * Strict allowlist for regionId values used as cache directory names.
     *
     * Matches: starts with `[a-z0-9]`, then any number of `[a-z0-9-]`.
     *
     * Excludes: `.`, `..`, leading hyphens, leading dots, slashes, backslashes,
     * empty strings, embedded null bytes, and anything not in the ASCII subset.
     * The kebab-case shape pairs with the slugifier in [BboxPickerFragment.save].
     */
    private val REGION_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

    /** Strict allowlist check; see [REGION_ID_PATTERN]. */
    fun isValidRegionId(regionId: String): Boolean = REGION_ID_PATTERN.matches(regionId)

    /** Returns the root directory of the cache. Creates it lazily; never null. */
    fun rootDir(): File {
        val storage = Environment.getExternalStorageDirectory()
            ?: error("External storage unavailable")
        val root = File(storage, DEFAULT_ROOT_NAME)
        if (!root.exists()) root.mkdirs()
        return root
    }

    /**
     * List regions currently present in the cache. Returns an empty list if
     * the cache is empty or unreadable. Never throws — the bottom-sheet tab
     * needs to render even if storage is in a bad state.
     */
    fun list(): List<RegionInfo> {
        val root = runCatching { rootDir() }.getOrNull() ?: return emptyList()
        val children = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return children.mapNotNull { dir ->
            runCatching { read(dir) }
                .onFailure { Log.w(TAG, "Skipping malformed region at ${dir.name}: ${it.message}") }
                .getOrNull()
        }.sortedBy { it.displayName.lowercase() }
    }

    /**
     * Delete a cached region directory recursively. Returns true if the
     * directory either was removed or did not exist (idempotent). Defensive
     * checks ensure the path is a child of the cache root before deletion —
     * catches `regionId` values that contain `..` or absolute paths.
     *
     * Validates [regionId] against [REGION_ID_PATTERN] first, then
     * canonicalises and asserts containment. The two checks are
     * complementary: the regex prevents most attacks at the API boundary,
     * canonicalisation catches anything the regex missed (defense in depth).
     */
    fun delete(regionId: String): Boolean {
        if (!isValidRegionId(regionId)) {
            Log.w(TAG, "Refusing to delete invalid regionId: $regionId")
            return false
        }
        val root = runCatching { rootDir() }.getOrNull() ?: return false
        val target = File(root, regionId).canonicalFile
        if (!target.toPath().startsWith(root.canonicalFile.toPath())) {
            Log.w(TAG, "Refusing to delete out-of-bounds path: $target")
            return false
        }
        if (!target.exists()) return true
        return target.deleteRecursively()
    }

    /** Best-effort read of `meta.json`. Falls back to disk-derived defaults. */
    private fun read(dir: File): RegionInfo {
        val metaFile = File(dir, "meta.json")
        val tilesFile = File(dir, "tiles.mbtiles")
        val poisFile = File(dir, "pois.json")

        val sizeOnDisk = sequenceOf(tilesFile, poisFile, metaFile)
            .filter { it.exists() }
            .sumOf { it.length() }

        val meta: JSONObject = if (metaFile.exists() && metaFile.length() > 0) {
            runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: JSONObject()
        } else JSONObject()

        // Optional bbox (south, west, north, east). Only surfaced when all four
        // values are present — partial / malformed bbox arrays are dropped.
        val bbox: DoubleArray? = runCatching {
            if (!meta.has("bbox")) return@runCatching null
            val arr: JSONArray = meta.getJSONArray("bbox")
            if (arr.length() != 4) return@runCatching null
            DoubleArray(4) { i -> arr.getDouble(i) }
        }.getOrNull()

        return RegionInfo(
            regionId = meta.optString("regionId", dir.name),
            displayName = meta.optString("displayName", dir.name),
            sizeBytes = if (meta.has("sizeBytes")) meta.optLong("sizeBytes", sizeOnDisk) else sizeOnDisk,
            downloadedAt = meta.optLong("downloadedAt", 0L).takeIf { it > 0L },
            lastUsedAt = meta.optLong("lastUsedAt", 0L).takeIf { it > 0L },
            source = meta.optString("source", "unknown"),
            directory = dir,
            bbox = bbox,
        )
    }
}

/**
 * What the bottom-sheet tab renders per row. Public because the
 * `RegionManagerFragment.Listener` overrides reference it; the *meaning* is
 * still plugin-internal (no other plugin should consume it).
 *
 * `bbox` is the south, west, north, east tuple read from `meta.json` — used
 * by Refresh to re-pick the same area without making the user re-draw.
 */
data class RegionInfo(
    val regionId: String,
    val displayName: String,
    val sizeBytes: Long,
    val downloadedAt: Long?,
    val lastUsedAt: Long?,
    val source: String,
    val directory: File,
    val bbox: DoubleArray? = null,
) {
    // bbox is a DoubleArray so equality needs structural comparison; the
    // generated equals/hashCode for data classes uses array reference equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegionInfo) return false
        if (regionId != other.regionId) return false
        if (displayName != other.displayName) return false
        if (sizeBytes != other.sizeBytes) return false
        if (downloadedAt != other.downloadedAt) return false
        if (lastUsedAt != other.lastUsedAt) return false
        if (source != other.source) return false
        if (directory != other.directory) return false
        if (bbox == null) {
            if (other.bbox != null) return false
        } else {
            if (other.bbox == null) return false
            if (!bbox.contentEquals(other.bbox)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = regionId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + (downloadedAt?.hashCode() ?: 0)
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        result = 31 * result + source.hashCode()
        result = 31 * result + directory.hashCode()
        result = 31 * result + (bbox?.contentHashCode() ?: 0)
        return result
    }
}
