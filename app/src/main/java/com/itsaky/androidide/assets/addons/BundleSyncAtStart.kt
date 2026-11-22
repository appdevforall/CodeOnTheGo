package com.itsaky.androidide.assets.addons

import android.content.Context
import com.itsaky.androidide.activities.areIdeToolsInstalled
import com.itsaky.androidide.assets.AssetsInstaller
import com.itsaky.androidide.assets.BundledAssetsInstaller
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.manager.loaders.PluginLoader
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [BundleSync] once per process, from the plugin system's start-up, before plugins load: the
 * synced set then loads once. [state] tells the main screen when to show a progress bar and when
 * the bundle's failures are recorded.
 */
object BundleSyncAtStart {
	enum class State {
		/** No check running; also the state before the plugin system starts. */
		IDLE,

		/** Comparing the build's bundle with the record. */
		CHECKING,

		/** Installing the build's bundle; ends once its plugins have loaded and failures are recorded. */
		SYNCING,
	}

	private val logger = LoggerFactory.getLogger(BundleSyncAtStart::class.java)
	private val mutableState = MutableStateFlow(State.IDLE)
	val state: StateFlow<State> = mutableState.asStateFlow()

	/** Call before launching the start-up coroutine, so a main screen that resumes first waits. */
	fun begin() {
		mutableState.value = State.CHECKING
	}

	/** Call when start-up is over, whatever happened. */
	fun end() {
		mutableState.value = State.IDLE
	}

	/**
	 * Syncs the build's bundle when it is not the recorded one. True when it synced. Never throws
	 * but for cancellation: on an error the old record stays, so the sync runs again next start.
	 * Leaves [state] at [State.SYNCING] after a sync, for [end] to clear once failures are recorded.
	 */
	fun run(
		context: Context,
		manager: PluginManager,
	): Boolean =
		try {
			// Before setup has finished, the asset phase installs the bundle (ADFA-7238).
			if (!areIdeToolsInstalled()) {
				false
			} else {
				withBundleSource(context) { source -> syncIfNeeded(context, manager, source) } ?: false
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			logger.error("Could not apply the bundled add-ons", e)
			false
		} finally {
			if (mutableState.value == State.CHECKING) end()
		}

	private fun syncIfNeeded(
		context: Context,
		manager: PluginManager,
		source: BundleSource,
	): Boolean {
		val record = BundledAddonsRecord.inFilesDir(context.filesDir)
		val uninstall = { pluginId: String ->
			manager.uninstallPlugin(pluginId)
			Unit
		}
		val sync =
			BundleSync(
				record = record,
				installer =
					BundledAddonInstaller(
						pluginsDir = File(context.filesDir, "plugins"),
						templatesDir = Environment.TEMPLATES_DIR,
						readPluginId = { PluginLoader(context, it).getPluginMetadata()?.id },
						beforePluginMove = uninstall,
					),
				templatesDir = Environment.TEMPLATES_DIR,
				uninstallPlugin = uninstall,
			)
		val entries = BundledAddons.list(source)
		if (!sync.isNeeded(entries)) return false
		mutableState.value = State.SYNCING
		val state = sync.sync(source, entries)
		logger.info("Applied the bundled add-ons: {} plugins, {} templates", state.pluginIds.size, state.templateFiles.size)
		return true
	}

	/**
	 * Runs [block] on the build's bundle. A debug build reads the split assets zip; when that is
	 * gone, nothing is known about the bundle and [block] does not run: deleting the zip after setup
	 * must not remove the bundled add-ons.
	 */
	private fun <T> withBundleSource(
		context: Context,
		block: (BundleSource) -> T,
	): T? {
		if (AssetsInstaller.CURRENT_INSTALLER is BundledAssetsInstaller) {
			return block(AssetManagerBundleSource(context.assets))
		}
		val zip = Environment.SPLIT_ASSETS_ZIP
		if (!zip.isFile) return null
		return ZipFile(zip).use { block(ZipBundleSource(it)) }
	}
}
