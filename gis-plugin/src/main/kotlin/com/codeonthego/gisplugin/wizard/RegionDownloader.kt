package com.codeonthego.gisplugin.wizard

import android.content.Context
import com.codeonthego.gisplugin.region.RegionCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Region download coordinator.
 *
 * Plan §5.2 has the live downloader pulling from OpenMapTiles HTTP for tiles
 * and Wikipedia REST `geosearch` + `summary` endpoints for POIs. The HTTP
 * implementation is a follow-up (open question O13 in the plan); for now this
 * class:
 *
 *  - creates the cache directory under
 *    `/sdcard/CodeOnTheGo/maps/<region-id>/`
 *  - writes a sentinel `tiles.mbtiles` (4 bytes — `MBT0`) so the file appears
 *    in `RegionCache.list()` with the correct size attribution
 *  - writes an empty-array `pois.json`
 *  - writes a real `meta.json` with the bbox, zoom range, source and
 *    timestamps so the bottom-sheet tab renders human-readable metadata
 *
 * The intent is to land the *cache layout contract* now so C3's UI flows can
 * be wired against real data, and so the eventual HTTP downloader is a
 * drop-in replacement here without touching the wizard or the bottom-sheet
 * tab.
 *
 * The download is exposed as a suspending function with a progress callback
 * (0.0–1.0). Today the callback fires on a synthetic timer (50 ms steps to
 * 100 %); when wired to real HTTP fetches it will report tile-completion
 * fractions.
 */
internal object RegionDownloader {

    /**
     * Stub download. Writes the cache layout described above and returns the
     * region directory.
     *
     * Reports synthetic progress so the wizard's progress bar animates and
     * the cancellation hook in the wizard has something to interrupt.
     *
     * @throws IllegalArgumentException if [regionId] contains path separators.
     */
    suspend fun download(
        @Suppress("UNUSED_PARAMETER") context: Context,
        regionId: String,
        displayName: String,
        bbox: Bbox,
        zoomMin: Int = 6,
        zoomMax: Int = 14,
        source: String = "openmaptiles-stub",
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require('/' !in regionId && '\\' !in regionId) { "regionId must not contain path separators" }

        val regionDir = File(RegionCache.rootDir(), regionId).apply { mkdirs() }

        val tiles = File(regionDir, "tiles.mbtiles")
        val pois = File(regionDir, "pois.json")
        val meta = File(regionDir, "meta.json")

        // Synthetic progress so the UI animates. C2/C3 swaps this for real
        // HTTP-backed iteration over tile coordinates.
        val steps = 20
        for (i in 1..steps) {
            delay(50)
            onProgress(i / steps.toFloat())
        }

        if (!tiles.exists()) tiles.writeBytes(byteArrayOf('M'.code.toByte(), 'B'.code.toByte(), 'T'.code.toByte(), '0'.code.toByte()))
        if (!pois.exists()) pois.writeText(JSONArray().toString())

        val now = System.currentTimeMillis()
        val sizeBytes = tiles.length() + pois.length()
        val metaJson = JSONObject().apply {
            put("regionId", regionId)
            put("displayName", displayName)
            put("bbox", JSONArray(bbox.toBoundsArray().toList()))
            put("zoomMin", zoomMin)
            put("zoomMax", zoomMax)
            put("source", source)
            put("sizeBytes", sizeBytes)
            put("downloadedAt", now)
            put("lastUsedAt", now)
        }
        meta.writeText(metaJson.toString(2))

        regionDir
    }
}
