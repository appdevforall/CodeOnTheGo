package com.itsaky.androidide.assets

import android.content.Context
import androidx.annotation.WorkerThread
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.app.configuration.CpuArch
import java.nio.file.Path

/**
 * Helper class to install assets for the IDE.
 */
sealed interface AssetsInstaller {

    companion object {

        /**
         * Whether to use bundled assets installer or not. If `true`, bundled assets installer will
         * be used.
         *
         * This is `true` for non-debug builds and for `instrumentation` builds. When updating this
         * value, please update the corresponding value in `AndroidModuleConf.kt`.
         */
        @Suppress("SimplifyBooleanWithConstants", "KotlinConstantConditions")
        private val USE_BUNDLED_ASSETS = !BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "instrumentation"

        /**
         * The current assets installer.
         */
        internal val CURRENT_INSTALLER = if (USE_BUNDLED_ASSETS) BundledAssetsInstaller else SplitAssetsInstaller
    }

    /**
     * Called before installing assets.
     *
     * @param context The application context.
     * @param stagingDir The staging directory for the assets.
     */
    suspend fun preInstall(context: Context, stagingDir: Path)

    /**
     * Called to install assets.
     *
     * @param context The application context.
     * @param stagingDir The staging directory for the assets.
     * @param cpuArch The CPU architecture of the device.
     * @param entryName The name of the entry to install.
     */
    @WorkerThread
    suspend fun doInstall(
        context: Context,
        stagingDir: Path,
        cpuArch: CpuArch,
        entryName: String,
    )

    /**
     * Called after installing assets.
     */
    suspend fun postInstall(context: Context, stagingDir: Path)
}
