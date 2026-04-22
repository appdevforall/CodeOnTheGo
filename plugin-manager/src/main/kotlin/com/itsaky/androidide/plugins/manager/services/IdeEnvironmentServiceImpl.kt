package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.utils.Environment
import java.io.File
import java.io.IOException

class IdeEnvironmentServiceImpl(
    private val pluginId: String
) : IdeEnvironmentService {

    override fun getIdeHomeDirectory(): File = Environment.ANDROIDIDE_HOME

    override fun getAndroidHomeDirectory(): File = Environment.ANDROID_HOME

    override fun getNdkDirectory(): File = Environment.NDK_DIR

    override fun getTmpDirectory(): File = Environment.TMP_DIR

    override fun getPluginDataDirectory(): File {
        val dir = File(File(Environment.ANDROIDIDE_HOME, PLUGIN_DATA_ROOT), "$pluginId/data")
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Failed to create plugin data directory: ${dir.absolutePath}")
        }
        return dir
    }

    private companion object {
        const val PLUGIN_DATA_ROOT = "plugins"
    }
}
