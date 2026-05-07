package com.codeonthego.gisplugin.wizard

import android.content.Context
import com.codeonthego.gisplugin.region.RegionCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 *
 * **Atomic-rename pattern.** Every file is staged to `<dest>.tmp` and then
 * `Files.move(tmp, dest, ATOMIC_MOVE)`-d into place. `meta.json` is the last
 * file written, so its presence implies tiles + pois are valid (per
 * REVIEW2.md theme #4 — transaction integrity). Refresh deletes the prior
 * payload before re-staging so an incomplete write doesn't leave orphan
 * `.tmp` files.
 */
internal object RegionDownloader {

    /**
     * Stub download. Writes the cache layout described above and returns the
     * region directory.
     *
     * Reports synthetic progress so the wizard's progress bar animates and
     * the cancellation hook in the wizard has something to interrupt.
     *
     * @throws IllegalArgumentException if [regionId] does not match the
     *   strict allowlist enforced by [RegionCache.isValidRegionId].
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
        require(RegionCache.isValidRegionId(regionId)) {
            "regionId '$regionId' is not a valid region identifier; must match [a-z0-9][a-z0-9-]*"
        }

        val regionDir = File(RegionCache.rootDir(), regionId).apply { mkdirs() }

        // Defense-in-depth: even after the regex check, canonicalise and
        // assert that regionDir resolves to a child of the cache root. Same
        // pattern RegionCache.delete() uses.
        val canonicalRoot = RegionCache.rootDir().canonicalFile.toPath()
        val canonicalRegion = regionDir.canonicalFile.toPath()
        require(canonicalRegion.startsWith(canonicalRoot)) {
            "regionDir escapes cache root: $canonicalRegion not under $canonicalRoot"
        }

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

        // Stage tiles + pois to .tmp, then atomic-rename so a kill mid-write
        // can't leave a half-written payload that look valid to read().
        val tilesContent = byteArrayOf(
            'M'.code.toByte(),
            'B'.code.toByte(),
            'T'.code.toByte(),
            '0'.code.toByte()
        )
        atomicWriteBytes(tiles, tilesContent)
        atomicWriteText(pois, JSONArray().toString())

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
        // Marker / metadata last — its presence is the "region is complete" signal.
        atomicWriteText(meta, metaJson.toString(2))

        regionDir
    }

    /**
     * Write [bytes] to [dest] atomically: write to `<dest>.tmp`, then
     * `Files.move(... ATOMIC_MOVE)`. If the move falls back (filesystem
     * doesn't support atomic moves), the destination is replaced via
     * `REPLACE_EXISTING` which is still all-or-nothing on most local FS.
     */
    private fun atomicWriteBytes(dest: File, bytes: ByteArray) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        if (tmp.exists()) tmp.delete()
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun atomicWriteText(dest: File, text: String) {
        atomicWriteBytes(dest, text.toByteArray(Charsets.UTF_8))
    }
}
