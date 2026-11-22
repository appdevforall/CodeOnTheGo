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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [BundleSync] at most once per process, from the plugin system's start-up, before plugins
 * load: the synced set then loads once. [state] tells the main screen when to show a progress bar
 * and when the bundle's failures are recorded.
 */
object BundleSyncAtStart {
	enum class State {
		/** The plugin system has not started yet; the main screen waits, up to a limit. */
		NOT_STARTED,

		/** Comparing the build's bundle with the record. */
		CHECKING,

		/** Installing the build's bundle; ends once its plugins have loaded and failures are recorded. */
		SYNCING,

		/** Start-up is over, or the plugin system will not start. */
		IDLE,
	}

	/** Failure key for a sync that did not finish; the error bar lists it like a plugin. */
	const val SYNC_FAILURE_KEY = "bundled add-ons"

	private val logger = LoggerFactory.getLogger(BundleSyncAtStart::class.java)
	private val mutableState = MutableStateFlow(State.NOT_STARTED)
	private val claimed = AtomicBoolean(false)
	val state: StateFlow<State> = mutableState.asStateFlow()

	/** Call before launching the start-up coroutine. */
	fun begin() {
		mutableState.value = State.CHECKING
	}

	/** Call when start-up is over, or when the plugin system fails to start, whatever happened. */
	fun end() {
		mutableState.value = State.IDLE
	}

	/**
	 * Syncs the build's bundle when it is not the recorded one. True when it synced. Never throws
	 * but for cancellation: on an error the old record stays, so the sync runs again next start,
	 * and the error is recorded for the error bar. Leaves [state] at [State.SYNCING] after a sync,
	 * for [end] to clear once failures are recorded.
	 */
	fun run(
		context: Context,
		manager: PluginManager,
	): Boolean {
		// A retried start-up (the loader un-claims itself on a failure) must not sync a second time.
		if (!claimed.compareAndSet(false, true)) return false
		val record = BundledAddonsRecord.inFilesDir(context.filesDir)
		return try {
			// Before setup has finished, the asset phase installs the bundle (ADFA-7238).
			if (!areIdeToolsInstalled()) {
				false
			} else {
				val release = AssetsInstaller.CURRENT_INSTALLER is BundledAssetsInstaller
				withBundleSource(release, Environment.SPLIT_ASSETS_ZIP, { AssetManagerBundleSource(context.assets) }) { source ->
					syncIfNeeded(context, manager, record, source)
				} ?: false
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			logger.error("Could not apply the bundled add-ons", e)
			// Only lands when a record exists; without one there is no installed bundle to fail.
			runCatching { record.recordFailures(mapOf(SYNC_FAILURE_KEY to (e.message ?: e.toString()))) }
			false
		} finally {
			if (mutableState.value == State.CHECKING) end()
		}
	}

	private fun syncIfNeeded(
		context: Context,
		manager: PluginManager,
		record: BundledAddonsRecord,
		source: BundleSource,
	): Boolean {
		val sync =
			BundleSync(
				record = record,
				pluginsDir = File(context.filesDir, "plugins"),
				templatesDir = Environment.TEMPLATES_DIR,
				readPluginId = { PluginLoader(context, it).getPluginMetadata()?.id },
				uninstallPlugin = { manager.uninstallPlugin(it) },
			)
		val entries = BundledAddons.list(source)
		if (!sync.isNeeded(entries)) return false
		mutableState.value = State.SYNCING
		val state = sync.sync(source, entries)
		logger.info("Applied the bundled add-ons: {} plugins, {} templates", state.pluginIds.size, state.templateFiles.size)
		return true
	}

	/**
	 * Runs [block] on the build's bundle: the APK assets in a release build, [splitZip] in a debug
	 * one. When the zip is gone, nothing is known about the bundle and [block] does not run:
	 * deleting the zip after setup must not remove the bundled add-ons.
	 */
	internal fun <T> withBundleSource(
		release: Boolean,
		splitZip: File,
		assets: () -> BundleSource,
		block: (BundleSource) -> T,
	): T? {
		if (release) return block(assets())
		if (!splitZip.isFile) return null
		return ZipFile(splitZip).use { block(ZipBundleSource(it)) }
	}
}
