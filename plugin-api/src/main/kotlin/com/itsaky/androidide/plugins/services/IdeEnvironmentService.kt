package com.itsaky.androidide.plugins.services

import java.io.File
import java.io.IOException

/**
 * Service interface that exposes IDE-managed directories to plugins.
 *
 * Reading these paths is unrestricted. Writing to them through
 * [IdeFileService] or [IdeArchiveService] requires the plugin to declare the
 * [com.itsaky.androidide.plugins.PluginPermission.IDE_ENVIRONMENT_WRITE]
 * permission in its manifest.
 */
interface IdeEnvironmentService {
    /**
     * Root of IDE-managed data. Holds caches, templates, snippets, tooling,
     * and per-plugin data.
     */
    fun getIdeHomeDirectory(): File

    /**
     * Root of the Android SDK installation managed by the IDE. Platforms,
     * build-tools, and the NDK live beneath this directory.
     */
    fun getAndroidHomeDirectory(): File

    /**
     * Directory where NDK installations reside. Equivalent to
     * `$ANDROID_HOME/ndk`.
     */
    fun getNdkDirectory(): File

    /**
     * Scratch directory for short-lived files such as staging archives
     * during extraction. Contents may be evicted at any time between plugin
     * activations; do not rely on persistence.
     */
    fun getTmpDirectory(): File

    /**
     * Per-plugin persistent data directory. Scoped to the calling plugin and
     * created on first access. Survives IDE restarts.
     *
     * @throws IOException if the directory does not exist and cannot be created.
     */
    @Throws(IOException::class)
    fun getPluginDataDirectory(): File
}
