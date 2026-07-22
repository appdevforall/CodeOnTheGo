package de.westnordost.streetcomplete.corpusharness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.util.math.distanceTo

/**
 * Harness scaffolding for the quick-build corpus (ADFA-4128, WS-A3) -- NOT part of upstream
 * StreetComplete. Exercises real domain-model + geometry-math code (LatLon, SphericalEarthMath's
 * distanceTo) without pulling in the full multiplatform app / Compose UI / Room persistence layer
 * (this corpus tier deliberately excludes those -- see README.md "streetcomplete-lib" for why).
 */
class GeoHostActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val distance = SamplePoints.START.distanceTo(SamplePoints.END)
		val view = TextView(this)
		view.text = "distance = $distance m"
		setContentView(view)
	}
}
