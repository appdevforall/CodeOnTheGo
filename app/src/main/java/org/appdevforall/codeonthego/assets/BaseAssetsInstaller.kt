package org.appdevforall.codeonthego.assets

import android.content.Context
import org.appdevforall.codeonthego.utils.Environment
import org.appdevforall.codeonthego.utils.FeatureFlags
import com.termux.shared.termux.TermuxConstants
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

abstract class BaseAssetsInstaller : AssetsInstaller {
	private val logger = LoggerFactory.getLogger(BaseAssetsInstaller::class.java)

	override suspend fun postInstall(
		context: Context,
		stagingDir: Path,
	) {
		for (bin in arrayOf(
			"aapt",
			"aapt2",
			"aidl",
            "apksigner",
            "d8",
			"dexdump",
			"split-select",
			"zipalign",
		)) {
			Environment.setExecutable(Environment.BUILD_TOOLS_DIR.resolve(bin))
		}
    }
}
