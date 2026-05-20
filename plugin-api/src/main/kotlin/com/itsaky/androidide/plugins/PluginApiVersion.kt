package com.itsaky.androidide.plugins

/**
 * Plugin API contract version (SemVer). Bump major for binary-incompatible changes
 * flagged by `:plugin-api:apiCheck`, minor for additive changes; then run
 * `:plugin-api:apiDump` to refresh `plugin-api/api/plugin-api.api`.
 */
@JvmInline
value class PluginApiVersion private constructor(val raw: String) : Comparable<PluginApiVersion> {

    val major: Int get() = parts()[0]
    val minor: Int get() = parts()[1]
    val patch: Int get() = parts()[2]

    override fun compareTo(other: PluginApiVersion): Int {
        val a = parts()
        val b = other.parts()
        if (a[0] != b[0]) return a[0].compareTo(b[0])
        if (a[1] != b[1]) return a[1].compareTo(b[1])
        return a[2].compareTo(b[2])
    }

    override fun toString(): String = raw

    private fun parts(): IntArray {
        val match = SEMVER.matchEntire(raw)
            ?: error("PluginApiVersion invariant broken: '$raw' was not validated at construction")
        return intArrayOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].ifEmpty { "0" }.toInt(),
            match.groupValues[3].ifEmpty { "0" }.toInt(),
        )
    }

    companion object {

        private val SEMVER = Regex("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$")

        val CURRENT: PluginApiVersion = parseOrThrow("1.0.0")

        fun parse(raw: String): PluginApiVersion? {
            val trimmed = raw.trim()
            return if (SEMVER.matches(trimmed)) PluginApiVersion(trimmed) else null
        }

        fun parseOrThrow(raw: String): PluginApiVersion =
            requireNotNull(parse(raw)) { "Invalid plugin API version: '$raw'" }
    }
}
