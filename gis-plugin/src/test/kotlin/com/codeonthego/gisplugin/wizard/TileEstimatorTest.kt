package com.codeonthego.gisplugin.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TileEstimator]. The slippy-map math is published at
 * https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames and is
 * load-bearing for the bbox picker's "X tiles · Y MB" estimate. Wrong
 * estimates make the user mistrust the size readout, which is one of the
 * most-visited UX surfaces in the wizard.
 *
 * Spot-checks: known tile-counts at known zoom levels for known bboxes.
 * Edge cases: zoom 0 (single tile worldwide), rejected zoom ranges,
 * coerceIn behaviour at the latitude clamp.
 */
class TileEstimatorTest {

    @Test
    fun zoomZeroIsExactlyOneTile() {
        // The whole world fits in tile (0, 0) at z=0. Any bbox should reduce
        // to one tile at this zoom.
        val box = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)
        val estimate = TileEstimator.estimate(box, zoomMin = 0, zoomMax = 0)
        assertEquals(1L, estimate.tileCount)
        assertEquals(0, estimate.zoomMin)
        assertEquals(0, estimate.zoomMax)
    }

    @Test
    fun zoomOneCoversFourTilesForFullWorld() {
        // Z=1 has 2×2 = 4 tiles for the whole world.
        val box = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)
        val estimate = TileEstimator.estimate(box, zoomMin = 1, zoomMax = 1)
        assertEquals(4L, estimate.tileCount)
    }

    @Test
    fun zoomTwoCoversSixteenTilesForFullWorld() {
        // Z=2 has 4×4 = 16 tiles.
        val box = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)
        val estimate = TileEstimator.estimate(box, zoomMin = 2, zoomMax = 2)
        assertEquals(16L, estimate.tileCount)
    }

    @Test
    fun zoomRangeSumsAcrossAllLevels() {
        // World at z=0..2: 1 + 4 + 16 = 21 tiles.
        val box = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)
        val estimate = TileEstimator.estimate(box, zoomMin = 0, zoomMax = 2)
        assertEquals(21L, estimate.tileCount)
    }

    @Test
    fun smallBoxAtMidLatitudeAtZoom14() {
        // Roughly a 5×5 km box at SF lat ~37.77 N. At z=14, a single
        // tile at this latitude is ~2.4 km on a side (1 lat-deg ~111 km
        // / 2^14 * 360 / cos(37.77)). 5x5 km should fit in roughly 4 tiles
        // wide × 3 tall.
        val box = Bbox(
            south = 37.7500, west = -122.4500,
            north = 37.7950, east = -122.4000
        )
        val estimate = TileEstimator.estimate(box, zoomMin = 14, zoomMax = 14)
        // Sanity bound; not a strict equality (the slippy projection has
        // latitude-dependent compression at z=14). Order of magnitude is
        // load-bearing.
        assertTrue(
            "Expected 5×5 km @ z14 to need 5–25 tiles, got ${estimate.tileCount}",
            estimate.tileCount in 5..25
        )
    }

    @Test
    fun sizeMbReflectsTileCount() {
        // At the configured 50 KB / tile, 100 tiles ≈ 4.88 MB.
        val box = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)
        val estimate = TileEstimator.estimate(box, zoomMin = 0, zoomMax = 3)
        // z=0..3: 1+4+16+64 = 85 tiles.
        assertEquals(85L, estimate.tileCount)
        // 85 * 50 KB = 4250 KB = 4.15 MB.
        assertEquals(4.15, estimate.sizeMb(), 0.05)
    }

    @Test
    fun displayStringContainsExpectedFields() {
        val estimate = TileEstimate(
            tileCount = 1024L, sizeBytesEstimate = 1024L * 50L * 1024L,
            zoomMin = 6, zoomMax = 14
        )
        val display = estimate.displayString()
        assertTrue(display.contains("1024 tiles"))
        assertTrue(display.contains("zoom 6"))
        assertTrue(display.contains("14"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZoomMinOutOfRange() {
        val box = Bbox(south = 0.0, west = 0.0, north = 1.0, east = 1.0)
        TileEstimator.estimate(box, zoomMin = -1, zoomMax = 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZoomMaxOutOfRange() {
        val box = Bbox(south = 0.0, west = 0.0, north = 1.0, east = 1.0)
        TileEstimator.estimate(box, zoomMin = 5, zoomMax = 21)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZoomMinGreaterThanMax() {
        val box = Bbox(south = 0.0, west = 0.0, north = 1.0, east = 1.0)
        TileEstimator.estimate(box, zoomMin = 10, zoomMax = 5)
    }

    @Test
    fun maxZoomDoesNotOverflowLong() {
        // Worst case: world bbox at z=0..14. 1 + 4 + 16 + ... + 4^14
        // = (4^15 - 1) / 3 = ~357,913,941 tiles. Should fit in Long.
        val box = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)
        val estimate = TileEstimator.estimate(box, zoomMin = 0, zoomMax = 14)
        assertTrue(
            "Tile count should be positive; got ${estimate.tileCount}",
            estimate.tileCount > 0
        )
        // Within a small slop because lat clamp at ±85 trims edges; should be
        // close to but not exceed the closed-form full-world sum.
        val fullWorldUpperBound = (1L shl 30)  // 4^15 ≈ 10^9
        assertTrue(
            "Tile count at z14 worldwide should be < 10^9, got ${estimate.tileCount}",
            estimate.tileCount < fullWorldUpperBound
        )
    }

    @Test
    fun smallBoxScalesWithZoom() {
        // Same 1° box; tile count should grow ~4x per zoom level.
        val box = Bbox(south = 0.0, west = 0.0, north = 1.0, east = 1.0)
        val z10 = TileEstimator.estimate(box, zoomMin = 10, zoomMax = 10).tileCount
        val z11 = TileEstimator.estimate(box, zoomMin = 11, zoomMax = 11).tileCount
        val z12 = TileEstimator.estimate(box, zoomMin = 12, zoomMax = 12).tileCount

        // 4× growth (with rounding), each step.
        assertTrue("z11=$z11 should be roughly 4× z10=$z10", z11 in (3 * z10)..(5 * z10))
        assertTrue("z12=$z12 should be roughly 4× z11=$z11", z12 in (3 * z11)..(5 * z11))
    }
}
