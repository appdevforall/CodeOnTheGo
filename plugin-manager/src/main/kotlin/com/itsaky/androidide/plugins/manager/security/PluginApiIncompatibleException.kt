package com.itsaky.androidide.plugins.manager.security

class PluginApiIncompatibleException(
    val pluginId: String,
    val requiredVersion: String,
    val availableVersion: String,
    val reason: Reason,
) : Exception(buildMessage(pluginId, requiredVersion, availableVersion, reason)) {

    enum class Reason {
        MAJOR_MISMATCH,
        REQUIRES_NEWER,
        MALFORMED_VERSION,
    }

    private companion object {
        fun buildMessage(
            pluginId: String,
            requiredVersion: String,
            availableVersion: String,
            reason: Reason,
        ): String = when (reason) {
            Reason.MAJOR_MISMATCH ->
                "Plugin '$pluginId' targets plugin API $requiredVersion, " +
                    "but this IDE provides $availableVersion (incompatible major version)."
            Reason.REQUIRES_NEWER ->
                "Plugin '$pluginId' requires plugin API $requiredVersion, " +
                    "but this IDE provides $availableVersion. Update the IDE to use this plugin."
            Reason.MALFORMED_VERSION ->
                "Plugin '$pluginId' declares an invalid plugin API version: '$requiredVersion'."
        }
    }
}
