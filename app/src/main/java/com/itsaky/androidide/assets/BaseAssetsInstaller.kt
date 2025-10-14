package com.itsaky.androidide.assets

import android.content.Context
import com.itsaky.androidide.utils.Environment
import java.nio.file.Path

abstract class BaseAssetsInstaller : AssetsInstaller {

    override suspend fun postInstall(
        context: Context,
        stagingDir: Path
    ) {
        Environment.AAPT2.setExecutable(true)
    }
}
