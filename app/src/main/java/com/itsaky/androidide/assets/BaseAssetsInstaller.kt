package com.itsaky.androidide.assets

import android.content.Context
import com.itsaky.androidide.utils.Environment
import java.nio.file.Path

abstract class BaseAssetsInstaller : AssetsInstaller {

    companion object {
        const val BUILD_TOOLS_VERSION = "35.0.0"
    }

    override suspend fun postInstall(
        context: Context,
        stagingDir: Path
    ) {
        // copy aapt2 binary
        val androidHome = Environment.ANDROID_HOME
        val buildToolsAapt2 =
            androidHome.resolve("build-tools")
                .resolve(BUILD_TOOLS_VERSION)
                .resolve("aapt2")

        Environment.AAPT2.also { aapt2 ->
            buildToolsAapt2.copyTo(aapt2)
            aapt2.setExecutable(true)
        }
    }
}
