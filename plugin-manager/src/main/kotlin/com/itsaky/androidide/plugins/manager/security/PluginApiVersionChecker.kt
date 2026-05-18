package com.itsaky.androidide.plugins.manager.security

internal object PluginApiVersionChecker {

    private val SEMVER = Regex("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$")

    fun isCompatible(required: String, current: String): Boolean {
        val r = parse(required) ?: return false
        val c = parse(current) ?: error("Invalid current plugin API version: '$current'")
        return r.major == c.major &&
            (r.minor < c.minor || (r.minor == c.minor && r.patch <= c.patch))
    }

    fun requireCompatible(pluginId: String, required: String, current: String) {
        val r = parse(required) ?: throw PluginApiIncompatibleException(
            pluginId = pluginId,
            requiredVersion = required,
            availableVersion = current,
            reason = PluginApiIncompatibleException.Reason.MALFORMED_VERSION,
        )
        val c = parse(current) ?: error("Invalid current plugin API version: '$current'")
        if (r.major != c.major) {
            throw PluginApiIncompatibleException(
                pluginId = pluginId,
                requiredVersion = required,
                availableVersion = current,
                reason = PluginApiIncompatibleException.Reason.MAJOR_MISMATCH,
            )
        }
        if (r.minor > c.minor || (r.minor == c.minor && r.patch > c.patch)) {
            throw PluginApiIncompatibleException(
                pluginId = pluginId,
                requiredVersion = required,
                availableVersion = current,
                reason = PluginApiIncompatibleException.Reason.REQUIRES_NEWER,
            )
        }
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int)

    private fun parse(raw: String): Version? {
        val match = SEMVER.matchEntire(raw.trim()) ?: return null
        return Version(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].ifBlank { "0" }.toInt(),
            patch = match.groupValues[3].ifBlank { "0" }.toInt(),
        )
    }
}
