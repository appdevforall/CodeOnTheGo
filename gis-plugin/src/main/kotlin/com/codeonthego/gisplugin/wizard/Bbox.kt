package com.codeonthego.gisplugin.wizard

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.floor

/**
 * WGS84 bounding box, ordered south, west, north, east. All in degrees.
 *
 * The wizard's bbox picker drives this object directly; the tile estimator
 * and downloader read from it. We keep a handcrafted `equals/hashCode`
 * (rather than `data class`) so any future addition of mutable derived state
 * doesn't accidentally break diff comparisons inside the bbox-picker UI.
 */
internal class Bbox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {

    init {
        require(south <= north) { "south ($south) must be <= north ($north)" }
        require(west <= east) { "west ($west) must be <= east ($east) — anti-meridian crossings unsupported in C2" }
    }

    /** Width of the box at its centre latitude, in kilometres. */
    fun widthKm(): Double {
        val midLat = (south + north) / 2.0
        return haversineKm(midLat, west, midLat, east)
    }

    /** Height of the box, in kilometres (latitude span × 111.32). */
    fun heightKm(): Double = haversineKm(south, west, north, west)

    fun toBoundsArray(): DoubleArray = doubleArrayOf(south, west, north, east)

    companion object {

        /**
         * Build a square bbox of the given edge length in km, centred on
         * (`lat`, `lon`). Used as the wizard's default bounding box (10 × 10
         * km around the user's GPS fix). Latitude span is straight; longitude
         * span is corrected for the cosine of the latitude so the *physical*
         * width matches.
         */
        fun aroundPoint(lat: Double, lon: Double, edgeKm: Double): Bbox {
            val halfDegLat = (edgeKm / 2.0) / 111.32
            val cosLat = max(0.01, cos(Math.toRadians(lat)))
            val halfDegLon = (edgeKm / 2.0) / (111.32 * cosLat)
            val south = (lat - halfDegLat).coerceIn(-85.0, 85.0)
            val north = (lat + halfDegLat).coerceIn(-85.0, 85.0)
            val west = (lon - halfDegLon).coerceIn(-180.0, 180.0)
            val east = (lon + halfDegLon).coerceIn(-180.0, 180.0)
            return Bbox(south, west, north, east)
        }
    }
}

/**
 * Compute the great-circle distance between two lat/lon points in kilometres.
 * Standard haversine; accurate enough for the wizard's "show MB estimate" UX
 * to single-digit-percent precision, which is all the user needs.
 */
internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0088
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2).pow(2)
    val c = 2 * kotlin.math.asin(kotlin.math.sqrt(a))
    return r * c
}

/**
 * Estimate the number of XYZ tiles covering [bbox] across [zoomMin]..[zoomMax].
 *
 * Uses standard slippy-map math (https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames):
 *   x = floor((lon + 180) / 360 * 2^z)
 *   y = floor((1 - ln(tan(lat) + sec(lat)) / π) / 2 * 2^z)
 *
 * Rough size guideline (per OpenMapTiles' published numbers, R2 ballpark):
 *   - vector tile averages ~30–80 KB at zoom 14, ~5–15 KB at zoom 6
 *   - rule-of-thumb 50 KB/tile across the zoom range; we expose [TILE_BYTES_AVG]
 *     so callers can tune.
 *
 * Returns a [TileEstimate] suitable for the wizard's live readout.
 */
internal object TileEstimator {

    private const val TILE_BYTES_AVG = 50L * 1024L  // 50 KB

    fun estimate(bbox: Bbox, zoomMin: Int = 6, zoomMax: Int = 14): TileEstimate {
        require(zoomMin in 0..20)
        require(zoomMax in zoomMin..20)
        var totalTiles = 0L
        for (z in zoomMin..zoomMax) {
            val (xMin, yMax) = lonLatToTile(bbox.west, bbox.north, z)
            val (xMax, yMin) = lonLatToTile(bbox.east, bbox.south, z)
            val xs = (xMax - xMin + 1).coerceAtLeast(0)
            val ys = (yMax - yMin + 1).coerceAtLeast(0)
            totalTiles += xs.toLong() * ys.toLong()
        }
        return TileEstimate(
            tileCount = totalTiles,
            sizeBytesEstimate = totalTiles * TILE_BYTES_AVG,
            zoomMin = zoomMin,
            zoomMax = zoomMax
        )
    }

    /** Slippy map x/y for a given lon/lat at zoom level [z]. */
    private fun lonLatToTile(lon: Double, lat: Double, z: Int): Pair<Int, Int> {
        val n = 2.0.pow(z)
        val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n.toInt() - 1)
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = floor((1 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n)
            .toInt().coerceIn(0, n.toInt() - 1)
        return x to y
    }
}

internal data class TileEstimate(
    val tileCount: Long,
    val sizeBytesEstimate: Long,
    val zoomMin: Int,
    val zoomMax: Int
) {
    fun sizeMb(): Double = sizeBytesEstimate / (1024.0 * 1024.0)

    fun displayString(): String = "$tileCount tiles · %.1f MB · zoom %d–%d".format(
        sizeMb(), zoomMin, zoomMax
    )
}
