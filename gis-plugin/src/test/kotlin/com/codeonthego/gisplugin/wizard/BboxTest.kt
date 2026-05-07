package com.codeonthego.gisplugin.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Bbox]. Covers:
 *  - construction guards (south <= north, west <= east, anti-meridian rejection).
 *  - [Bbox.contains] — boundary-inclusive containment, edge cases at poles + meridians.
 *  - [Bbox.widthKm] / [Bbox.heightKm] / [Bbox.areaKm2] math sanity.
 *  - [Bbox.aroundPoint] — equator vs. high-latitude longitude scaling, latitude clamp.
 */
class BboxTest {

    // ----- construction guards -----

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSouthGreaterThanNorth() {
        Bbox(south = 10.0, west = 0.0, north = 5.0, east = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWestGreaterThanEast() {
        // Anti-meridian crossing: west = 170, east = -170 — unsupported per init.
        Bbox(south = 0.0, west = 170.0, north = 1.0, east = -170.0)
    }

    @Test
    fun acceptsPointBox() {
        // Degenerate: south == north, west == east. Lat / lon clamps in
        // aroundPoint can produce these at extreme inputs and we want them
        // to round-trip rather than throw.
        val pt = Bbox(south = 0.0, west = 0.0, north = 0.0, east = 0.0)
        assertEquals(0.0, pt.widthKm(), 0.0001)
        assertEquals(0.0, pt.heightKm(), 0.0001)
    }

    // ----- contains() -----

    @Test
    fun containsInteriorPoint() {
        val box = Bbox(south = 10.0, west = 20.0, north = 30.0, east = 40.0)
        assertTrue(box.contains(20.0, 30.0))
        assertTrue(box.contains(15.5, 25.5))
    }

    @Test
    fun containsBoundaryInclusive() {
        val box = Bbox(south = 10.0, west = 20.0, north = 30.0, east = 40.0)
        // All four corners.
        assertTrue(box.contains(10.0, 20.0))
        assertTrue(box.contains(30.0, 20.0))
        assertTrue(box.contains(10.0, 40.0))
        assertTrue(box.contains(30.0, 40.0))
        // Edge midpoints.
        assertTrue(box.contains(10.0, 30.0))
        assertTrue(box.contains(30.0, 30.0))
        assertTrue(box.contains(20.0, 20.0))
        assertTrue(box.contains(20.0, 40.0))
    }

    @Test
    fun excludesPointsOutsideBox() {
        val box = Bbox(south = 10.0, west = 20.0, north = 30.0, east = 40.0)
        assertFalse(box.contains(9.999, 30.0))
        assertFalse(box.contains(30.001, 30.0))
        assertFalse(box.contains(20.0, 19.999))
        assertFalse(box.contains(20.0, 40.001))
        assertFalse(box.contains(0.0, 0.0))
    }

    @Test
    fun containsEdgeCasesAtPoles() {
        // Box extending to the latitude clamp (±85). aroundPoint clamps at
        // ±85 explicitly; init's bounds are -90..90 nominally.
        val polar = Bbox(south = 80.0, west = -10.0, north = 85.0, east = 10.0)
        assertTrue(polar.contains(85.0, 0.0))
        assertTrue(polar.contains(80.0, 0.0))
        assertFalse(polar.contains(85.001, 0.0))
    }

    @Test
    fun containsEdgeCasesAtMeridian() {
        // Box adjacent to the antimeridian — but strictly east of it, since
        // crossing the antimeridian is rejected at construction.
        val nearEdge = Bbox(south = 0.0, west = 175.0, north = 5.0, east = 180.0)
        assertTrue(nearEdge.contains(2.0, 180.0))
        assertTrue(nearEdge.contains(2.0, 175.0))
        assertFalse(nearEdge.contains(2.0, -180.0)) // -180 != 180 in this model.
    }

    // ----- width / height / area math -----

    @Test
    fun widthAndHeightForOneDegreeBoxAtEquator() {
        // 1° at the equator ≈ 111.32 km in both axes. Tolerance ~1 km
        // because haversine uses 6371.0088 km radius.
        val box = Bbox(south = 0.0, west = 0.0, north = 1.0, east = 1.0)
        assertEquals(111.0, box.widthKm(), 1.0)
        assertEquals(111.0, box.heightKm(), 1.0)
        // Area ≈ 12,300 km² for a degree square at the equator.
        assertEquals(12_321.0, box.areaKm2(), 200.0)
    }

    @Test
    fun widthShrinksWithLatitudeFromCosine() {
        // Same 1° lon span but farther from equator should be narrower in km.
        val equator = Bbox(south = 0.0, west = 0.0, north = 1.0, east = 1.0)
        val midLat = Bbox(south = 45.0, west = 0.0, north = 46.0, east = 1.0)
        assertTrue(midLat.widthKm() < equator.widthKm())
        // cos(45°) ≈ 0.707; 1° at 45° ≈ 78 km.
        assertEquals(78.0, midLat.widthKm(), 2.0)
        // Height stays ~111 km regardless of latitude.
        assertEquals(equator.heightKm(), midLat.heightKm(), 0.5)
    }

    // ----- aroundPoint() -----

    @Test
    fun aroundPointBuildsApproximateSquareAtEquator() {
        // 10×10 km box around the equator: should be ~0.09° on each side.
        val box = Bbox.aroundPoint(0.0, 0.0, 10.0)
        assertEquals(10.0, box.widthKm(), 0.5)
        assertEquals(10.0, box.heightKm(), 0.5)
    }

    @Test
    fun aroundPointWidensLongitudeAtHighLatitudes() {
        // 10×10 km at 60° latitude. Latitude span ≈ 0.09°; longitude span
        // ≈ 0.09° / cos(60°) = 0.18°.
        val box = Bbox.aroundPoint(60.0, 0.0, 10.0)
        val latSpan = box.north - box.south
        val lonSpan = box.east - box.west
        // Longitude span should be roughly 2x latitude span at lat=60.
        assertTrue(
            "Expected lonSpan ≈ 2 × latSpan at lat=60°, got latSpan=$latSpan lonSpan=$lonSpan",
            lonSpan > latSpan * 1.8 && lonSpan < latSpan * 2.2
        )
        // Physical width should still be ~10 km.
        assertEquals(10.0, box.widthKm(), 0.5)
        assertEquals(10.0, box.heightKm(), 0.5)
    }

    @Test
    fun aroundPointClampsLatitudeAtPoles() {
        // Centred near the pole, 1000 km box. Latitude must clamp at ±85
        // per the production code (Web-Mercator-friendly).
        val box = Bbox.aroundPoint(89.0, 0.0, 1000.0)
        assertTrue(box.south >= -85.0)
        assertTrue(box.north <= 85.0)
    }

    @Test
    fun aroundPointPreventsCosLatBlowup() {
        // At 90° latitude cos = 0; the production code floors at 0.01 to
        // keep the longitude division finite. The result is constructable
        // (no infinity, no NaN) which is the contract under test here.
        val box = Bbox.aroundPoint(90.0, 0.0, 10.0)
        assertNotNull(box)
        assertTrue(box.east.isFinite())
        assertTrue(box.west.isFinite())
        assertTrue(box.north.isFinite())
        assertTrue(box.south.isFinite())
    }

    @Test
    fun toBoundsArrayRoundTripsConstructor() {
        val box = Bbox(south = -10.0, west = -20.0, north = 30.0, east = 40.0)
        val arr = box.toBoundsArray()
        assertEquals(4, arr.size)
        assertEquals(-10.0, arr[0], 0.0)
        assertEquals(-20.0, arr[1], 0.0)
        assertEquals(30.0, arr[2], 0.0)
        assertEquals(40.0, arr[3], 0.0)

        // Reconstruct.
        val reconstructed = Bbox(arr[0], arr[1], arr[2], arr[3])
        assertEquals(box.south, reconstructed.south, 0.0)
        assertEquals(box.east, reconstructed.east, 0.0)
    }

    // ----- haversine sanity -----

    @Test
    fun haversineKmKnownDistance() {
        // SF (37.7749, -122.4194) to NYC (40.7128, -74.0060) ≈ 4129 km.
        val km = haversineKm(37.7749, -122.4194, 40.7128, -74.0060)
        assertEquals(4129.0, km, 5.0)
    }

    @Test
    fun haversineKmZeroForSamePoint() {
        assertEquals(0.0, haversineKm(45.0, 90.0, 45.0, 90.0), 0.0001)
    }
}
