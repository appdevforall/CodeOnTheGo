package com.itsaky.androidide.assets

import android.content.Context
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.GRADLE_DISTRIBUTION_NAME
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

		// Both stores are on disk by now, which is the first point their overlap can be seen.
		// This also runs after a FAILED install (the caller's finally), so the tree may be half
		// extracted - safe, because a partial jar cannot hash equal to the distribution's.
		withContext(Dispatchers.IO) {
			DistJarDeduplicator.deduplicate(
				mavenRepo = Environment.LOCAL_MAVEN_DIR,
				distLib = File(Environment.GRADLE_DISTS, "$GRADLE_DISTRIBUTION_NAME/lib"),
			)
		}
	}
}
