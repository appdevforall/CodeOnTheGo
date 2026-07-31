package com.itsaky.androidide.di

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.itsaky.androidide.analytics.quickbuild.AnalyticsQuickBuildMetricsSink
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.quickbuild.AndroidInstalledPackages
import com.itsaky.androidide.quickbuild.AndroidTestAppLauncher
import com.itsaky.androidide.quickbuild.ApkSigningCert
import com.itsaky.androidide.quickbuild.BenchEventsFile
import com.itsaky.androidide.quickbuild.BenchQuickBuildMetricsSink
import com.itsaky.androidide.quickbuild.BenchStateRecorder
import com.itsaky.androidide.quickbuild.CompositeQuickBuildMetricsSink
import com.itsaky.androidide.quickbuild.EnvironmentQuickBuildPaths
import com.itsaky.androidide.quickbuild.GradleQuickBuildProvisioner
import com.itsaky.androidide.quickbuild.InstallationEventFlow
import com.itsaky.androidide.quickbuild.PreferencesQuickBuildHistoryStore
import com.itsaky.androidide.utils.ApkInstaller
import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.quickbuild.data.DaemonProcessClient
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.service.DeployChannel
import org.appdevforall.cotg.quickbuild.service.DeploySender
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.QuickBuildClobberCheck
import org.appdevforall.cotg.quickbuild.service.QuickBuildHistoryStore
import org.appdevforall.cotg.quickbuild.service.QuickBuildProvisioner
import org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager
import org.appdevforall.cotg.quickbuild.service.TestAppConnections
import org.appdevforall.cotg.quickbuild.service.TestAppInstaller
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.Executors

/**
 * Koin wiring for Quick Build (ADFA-4128). Everything is a lazy singleton: nothing
 * spawns a process or binds a service until the first lightning-bolt tap resolves the
 * session manager.
 */
val quickBuildModule =
	module {
		// The Android-instantiated QuickBuildHostService writes into the same
		// process-wide registry, so the graph must bind exactly that instance.
		single { TestAppConnections.INSTANCE }

		single { EnvironmentQuickBuildPaths(androidContext()) }

		single<QuickBuildDaemon> {
			DaemonProcessClient(
				paths = get<EnvironmentQuickBuildPaths>(),
				scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
			)
		}

		single<DeploySender> { DeployChannel(get()) }

		single<InstalledPackages> { AndroidInstalledPackages(androidContext()) }

		single<QuickBuildHistoryStore> {
			PreferencesQuickBuildHistoryStore(
				context = androidContext(),
				projectPath = { runCatching { IProjectManager.getInstance().projectDirPath }.getOrNull() },
			)
		}

		// Confirm-on-switch check: reads which build (Quick Build test app vs Standard Run)
		// currently occupies the real applicationId, so the UI can warn before a clobber.
		single { QuickBuildClobberCheck(get<InstalledPackages>()) }

		single {
			val context = androidContext()
			TestAppInstaller(
				packages = get(),
				// The exact call the Run button's install flow bottoms out in (plan B1):
				// same PackageInstaller session params, same InstallationResultReceiver,
				// same MIUI intent fallback.
				launchInstall = { apk ->
					withContext(Dispatchers.Main) { ApkInstaller.installApk(context, apk) }
				},
				// Register before any install: the receiver's EventBus events become the
				// installer's completion signal.
				broadcasts = InstallationEventFlow().also { it.register() }.broadcasts,
				// Whether the install-confirm dialog can be launched right now. The
				// dialog-owning subscriber (BaseEditorActivity -> InstallationResultHandler)
				// is EventBus lifecycle-bound - registered onStart, unregistered onStop -
				// so it can show the dialog exactly while the process is STARTED. Racy
				// reads err toward waiting (the installer's timeout is the backstop).
				canShowConfirmDialog = {
					ProcessLifecycleOwner
						.get()
						.lifecycle.currentState
						.isAtLeast(Lifecycle.State.STARTED)
				},
			)
		}

		single<QuickBuildProvisioner> {
			val context = androidContext()
			GradleQuickBuildProvisioner(
				context = context,
				paths = get<EnvironmentQuickBuildPaths>(),
				installer = get<TestAppInstaller>(),
				packages = get(),
				apkCertSha256 = { apk -> ApkSigningCert.sha256(context, apk) },
			)
		}

		// Shared JSON-lines writer for the ADFA-4128 harness; lazily created (only when a
		// bench-flag branch below resolves it), so shipping builds never touch it.
		single { BenchEventsFile(get<EnvironmentQuickBuildPaths>().benchEventsFile) }

		single<QuickBuildMetricsSink> {
			val analytics =
				AnalyticsQuickBuildMetricsSink(
					analytics = get(),
					projectPath = { IProjectManager.getInstance().projectDirPath },
					// David's multi-module-encounter-rate ask (ADFA-4128 status 2026-07-27):
					// forwarded as a plain count so "multi-module" reads as moduleCount > 1
					// without a new event. Counts ALL Gradle subprojects via the public
					// IProjectManager.workspace (an app module plus a pure-JVM library IS
					// multi-module); null - omitted, never 0 - until the workspace syncs.
					moduleCount = {
						IProjectManager
							.getInstance()
							.workspace
							?.subProjects
							?.size
					},
				)
			if (FeatureFlags.isQuickBuildBenchEnabled) {
				// Bench: fan the analytics sink AND a JSON-lines file so an external run
				// reads timings over adb; shipping behavior is the analytics sink alone.
				CompositeQuickBuildMetricsSink(analytics, BenchQuickBuildMetricsSink(get<BenchEventsFile>()))
			} else {
				analytics
			}
		}

		single {
			QuickBuildSessionManager(
				daemon = get(),
				deploy = get(),
				provisioner = get(),
				connections = get(),
				paths = get<EnvironmentQuickBuildPaths>(),
				historyStore = get(),
				// The orchestrator's ordering guarantee requires a single-threaded
				// dispatcher (see BuildOrchestrator KDoc); a dedicated thread keeps
				// session work off Main and off the shared pools.
				dispatcher =
					Executors
						.newSingleThreadExecutor { runnable ->
							Thread(runnable, "QuickBuildSession")
						}.asCoroutineDispatcher(),
				metrics = get(),
				// Restart deploys (service/provider/Application code changed): the
				// runtime exits after persisting; this relaunches the launcher proxy.
				launcher = AndroidTestAppLauncher(androidContext()),
				// Monotonic device clock for the e2e timing line (ADFA-4128); the module
				// default is JVM currentTimeMillis for unit tests.
				nowMillis = SystemClock::elapsedRealtime,
				// Bench A/B seam: CodeOnTheGo.qbnoseed suppresses the post-provisioning
				// background seed, but only when the bench flag is also on - shipping
				// builds (no qbbench) always seed.
				backgroundSeedEnabled = {
					!(FeatureFlags.isQuickBuildBenchEnabled && FeatureFlags.isQuickBuildSeedDisabled)
				},
			).also { manager ->
				if (FeatureFlags.isQuickBuildBenchEnabled) {
					// ADFA-4128 harness: a second, read-only collector on the existing state
					// stream, writing one JSON line per state change. The UI's own collector
					// is untouched.
					BenchStateRecorder(get<BenchEventsFile>())
						.attach(manager.state, CoroutineScope(SupervisorJob() + Dispatchers.IO))
				}
			}
		}
	}
