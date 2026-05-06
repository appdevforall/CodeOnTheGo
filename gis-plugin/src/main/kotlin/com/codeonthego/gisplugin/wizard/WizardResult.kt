package com.codeonthego.gisplugin.wizard

/**
 * Outcome of running the GIS wizard. Returned to the recipe once the wizard
 * finishes (or null on cancellation).
 *
 * C1 only carries a sentinel — later commits will populate the region
 * selection and download metadata. Currently a plain `data class`; if a future
 * commit needs to round-trip the result through an Intent extra (e.g. for an
 * `ActivityResultContract`-based variant), we can swap to `@Parcelize` and
 * add the kotlin-parcelize Gradle plugin then. For C1's static-callback
 * approach (see [WizardLauncher]), Parcelable buys us nothing.
 */
data class WizardResult(
    /** Region id selected or freshly downloaded by the wizard, in `kebab-case` (e.g. `lalibela-eth`). */
    val regionId: String,

    /** Bounding box in WGS84, ordered south, west, north, east. May be empty for the C1 stub. */
    val bbox: DoubleArray = DoubleArray(0),

    /** Source of the region — `"download"`, `"cache"`, or `"stub"` for C1. */
    val source: String = "stub"
) {

    // equals/hashCode handcrafted because DoubleArray uses identity equality by default.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WizardResult
        if (regionId != other.regionId) return false
        if (!bbox.contentEquals(other.bbox)) return false
        if (source != other.source) return false
        return true
    }

    override fun hashCode(): Int {
        var result = regionId.hashCode()
        result = 31 * result + bbox.contentHashCode()
        result = 31 * result + source.hashCode()
        return result
    }
}
