/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.itsaky.androidide.handlers

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.DeviceUtils
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.utils.BuildInfoUtils
import com.termux.shared.android.PackageUtils
import com.termux.shared.android.SELinuxUtils
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.slf4j.LoggerFactory

/**
 * Enriches every crash event sent to GlitchTip with app-specific diagnostic
 * context (SELinux labels, boot mode, install location, signing certificate,
 * active plugins, release/ABI/device posture). GlitchTip is the backend; the
 * `io.sentry` SDK is the client we report through (GlitchTip speaks the Sentry
 * protocol), so the event types below are the SDK's.
 *
 * The context is attached through a single [EventProcessor] registered in
 * [io.sentry.android.core.SentryAndroid.init]. Running at capture time means a
 * single processor sees the live boot state and the currently loaded plugins
 * for fatal crashes, caught exceptions and plugin crashes alike, with no
 * per-call-site wiring.
 *
 * Every field is collected inside its own [runCatching] so that a single
 * failing collector (e.g. an SELinux read denied by policy, or credential
 * protected storage being inaccessible in direct boot) drops only that one
 * field — the event is still reported with everything else intact.
 *
 * No source code or project file paths are ever read. The SELinux file-context
 * collector keeps only the returned security label, not the directory path.
 *
 * @author Hal Eisen
 */
object GlitchTipDiagnosticsContext {
	private val log = LoggerFactory.getLogger(GlitchTipDiagnosticsContext::class.java)

	/** Process/boot start stamp, used to compute the direct-boot locked duration. */
	private val bootElapsedStartMs = SystemClock.elapsedRealtime()

	/** Elapsed-time stamp of when the user credential-unlocked the device, if observed. */
	@Volatile
	private var userUnlockedElapsedMs: Long? = null

	/** Whether the app process started while the device was still credential-locked. */
	@Volatile
	private var startedInDirectBoot = false

	// --- Static per-process values, computed once and cached lazily. ---

	private val appInfo: ApplicationInfo? by lazy {
		val app = IDEApplication.instance
		PackageUtils.getApplicationInfoForPackage(app, app.packageName)
	}

	private val seInfo: String? by lazy {
		appInfo?.let { PackageUtils.getApplicationInfoSeInfoForPackage(it) }
	}

	private val signingDigest: String? by lazy {
		val app = IDEApplication.instance
		PackageUtils.getSigningCertificateSHA256DigestForPackage(app, app.packageName)
	}

	private val installLocation: String? by lazy {
		appInfo?.let {
			if (PackageUtils.isAppInstalledOnExternalStorage(it)) "external" else "internal"
		}
	}

	/**
	 * Registers the diagnostics [EventProcessor] on the given Sentry [options].
	 * Call once from within `SentryAndroid.init`.
	 */
	fun install(options: SentryOptions) {
		// Capture whether we started locked at install time; install() runs from
		// DeviceProtectedApplicationLoader, which is reachable in direct boot.
		startedInDirectBoot = runCatching { !IDEApplication.instance.isUserUnlocked }.getOrDefault(false)

		options.addEventProcessor(
			object : EventProcessor {
				override fun process(
					event: SentryEvent,
					hint: Hint,
				): SentryEvent {
					runCatching { enrich(event) }.onFailure { log.warn("Failed to enrich GlitchTip event", it) }
					return event
				}
			},
		)
	}

	/**
	 * Stamps the moment the device transitioned to credential-unlocked, so the
	 * direct-boot locked duration can be reported. Idempotent — only the first
	 * unlock is recorded.
	 */
	fun onUserUnlocked() {
		if (userUnlockedElapsedMs == null) {
			userUnlockedElapsedMs = SystemClock.elapsedRealtime()
		}
	}

	private fun enrich(event: SentryEvent) {
		val app = runCatching { IDEApplication.instance }.getOrNull() ?: return

		// ① SELinux contexts (process, private-data-dir file label, seinfo).
		context(event, "selinux") {
			buildMap {
				runCatching { SELinuxUtils.getContext() }.getOrNull()?.let { put("process_context", it) }
				runCatching { SELinuxUtils.getFileContext(app.filesDir.absolutePath) }
					.getOrNull()
					?.let { put("file_context", it) }
				runCatching { seInfo }.getOrNull()?.let { put("seinfo", it) }
			}
		}

		// ② Boot mode, queried live, plus the locked duration if we started locked.
		tag(event, "boot_mode") {
			if (app.isUserUnlocked) "credential_unlocked" else "direct_boot"
		}
		if (startedInDirectBoot) {
			tag(event, "boot_locked_duration_ms") {
				val end = userUnlockedElapsedMs ?: SystemClock.elapsedRealtime()
				(end - bootElapsedStartMs).toString()
			}
		}

		// ③ Install location (internal vs external/SD).
		tag(event, "install_location") { installLocation }

		// ④ Signing certificate digest + official/unofficial build flag.
		tag(event, "signing_sha256") { signingDigest }
		tag(event, "signing_official") { BuildInfoUtils.isOfficialBuild(app).toString() }

		// ⑤ Active plugins (enabled + loaded) with version and recent crash count.
		context(event, "active_plugins") {
			val pm = PluginManager.getInstance() ?: return@context null
			val plugins =
				pm
					.getAllPlugins()
					.filter { it.isEnabled && it.isLoaded }
					.map { info ->
						mapOf(
							"id" to info.metadata.id,
							"version" to info.metadata.version,
							"min_ide_version" to info.metadata.minIdeVersion,
							"crash_count" to
								runCatching {
									pm.crashTracker.getCrashCount(info.metadata.id)
								}.getOrDefault(0),
						)
					}
			mapOf("count" to plugins.size, "plugins" to plugins)
		}

		// A — release identifier + version code.
		tag(event, "app_version_name") { BuildInfo.VERSION_NAME_SIMPLE }
		tag(event, "app_version_code") { AppUtils.getAppVersionCode().toString() }
		tag(event, "app_git_commit") { BuildInfo.CI_GIT_COMMIT_HASH }
		tag(event, "app_ci_build") { BuildInfo.CI_BUILD.toString() }

		// B — process ABI / variant.
		val buildConfig = IDEBuildConfigProvider.getInstance()
		tag(event, "abi") { buildConfig.cpuAbiName }
		tag(event, "cpu_arch") { buildConfig.cpuArch.name }

		// H — device posture (emulator vs physical, rooted).
		tag(event, "device_emulator") { DeviceUtils.isEmulator().toString() }
		tag(event, "device_rooted") { DeviceUtils.isDeviceRooted().toString() }
	}

	/** Sets a single tag, guarded so a failing collector drops only that tag. */
	private inline fun tag(
		event: SentryEvent,
		key: String,
		value: () -> String?,
	) {
		runCatching { value()?.let { event.setTag(key, it) } }
			.onFailure { log.debug("GlitchTip diagnostics: dropped tag '{}'", key, it) }
	}

	/**
	 * Attaches a structured context group, guarded so a failing collector drops
	 * only that group. Empty/null maps are skipped.
	 */
	private inline fun context(
		event: SentryEvent,
		key: String,
		value: () -> Map<String, Any?>?,
	) {
		runCatching { value()?.takeIf { it.isNotEmpty() }?.let { event.contexts.put(key, it) } }
			.onFailure { log.debug("GlitchTip diagnostics: dropped context '{}'", key, it) }
	}
}
