package com.itsaky.androidide.plugins.manager.security

import com.itsaky.androidide.plugins.PluginApiVersion

internal object PluginApiVersionChecker {

    fun isCompatible(required: PluginApiVersion, current: PluginApiVersion): Boolean {
        if (required.major != current.major) return false
        return required <= current
    }

    fun requireCompatible(pluginId: String, requiredRaw: String, current: PluginApiVersion) {
        val required = PluginApiVersion.parse(requiredRaw) ?: throw PluginApiIncompatibleException(
            pluginId = pluginId,
            requiredVersion = requiredRaw,
            availableVersion = current.raw,
            reason = PluginApiIncompatibleException.Reason.MALFORMED_VERSION,
        )
        requireCompatible(pluginId, required, current)
    }

    fun requireCompatible(pluginId: String, required: PluginApiVersion, current: PluginApiVersion) {
        fun reject(reason: PluginApiIncompatibleException.Reason): Nothing =
            throw PluginApiIncompatibleException(
                pluginId = pluginId,
                requiredVersion = required.raw,
                availableVersion = current.raw,
                reason = reason,
            )

        if (required.major != current.major) reject(PluginApiIncompatibleException.Reason.MAJOR_MISMATCH)
        if (required > current) reject(PluginApiIncompatibleException.Reason.REQUIRES_NEWER)
    }
}
