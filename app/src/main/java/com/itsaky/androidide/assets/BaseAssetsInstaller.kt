package com.itsaky.androidide.assets

import android.content.Context
import com.itsaky.androidide.assets.addons.BundleSource
import com.itsaky.androidide.assets.addons.BundledAddonInstaller
import com.itsaky.androidide.assets.addons.BundledAddons
import com.itsaky.androidide.assets.addons.BundledEntry
import com.itsaky.androidide.assets.addons.InstalledAddon
import com.itsaky.androidide.plugins.manager.loaders.PluginLoader
import com.itsaky.androidide.utils.Environment
import com.termux.shared.termux.TermuxConstants
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

abstract class BaseAssetsInstaller : AssetsInstaller {
	private val logger = LoggerFactory.getLogger(BaseAssetsInstaller::class.java)
	private val installedByEntry = ConcurrentHashMap<String, InstalledAddon>()

	// Content hashes from the bundle index, by entry name, for the copies to check.
	private val sha256ByEntry = ConcurrentHashMap<String, String>()

	/** Where this installer's bundled add-ons are read from. */
	protected abstract fun bundleSource(context: Context): BundleSource

	override fun listBundledAddons(context: Context): List<BundledEntry> {
		installedByEntry.clear()
		sha256ByEntry.clear()
		return BundledAddons.list(bundleSource(context)).onEach { entry ->
			entry.sha256?.let { sha256ByEntry[entry.entryName] = it }
		}
	}

	override fun installedAddons(): List<InstalledAddon> = installedByEntry.values.toList()

	/** Installs the bundled add-on [entryName] (`plugins/<file>` or `templates/<file>`) read from [input]. */
	protected fun installBundledAddon(
		context: Context,
		entryName: String,
		input: InputStream,
	) {
		val (kind, fileName) =
			BundledAddons.parse(entryName) ?: throw IllegalStateException("Unknown entry: $entryName")
		val installer =
			BundledAddonInstaller(
				// The folder PluginManager loads from.
				pluginsDir = File(context.filesDir, "plugins"),
				templatesDir = Environment.TEMPLATES_DIR,
				readPluginId = { PluginLoader(context, it).getPluginMetadata()?.id },
				signatureOf = { PluginLoader(context, it).getSignatureHash() },
			)
		installedByEntry[entryName] = installer.install(kind, fileName, input, sha256ByEntry[entryName])
	}

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
