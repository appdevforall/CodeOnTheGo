package de.westnordost.streetcomplete.corpusharness

import de.westnordost.streetcomplete.data.osm.mapdata.LatLon

/**
 * Harness scaffolding (ADFA-4128, WS-A3) -- two fixed points for [GeoHostActivity] to compute a
 * real distance with. Not part of upstream StreetComplete.
 */
object SamplePoints {
	val START = LatLon(52.5200, 13.4050) // Berlin
	val END = LatLon(48.8566, 2.3522) // Paris
}
