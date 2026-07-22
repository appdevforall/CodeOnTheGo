package com.itsaky.androidide.di

import com.itsaky.androidide.analytics.quickbuild.AnalyticsQuickBuildMetricsSink
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.quickbuild.AndroidInstalledPackages
import com.itsaky.androidide.quickbuild.AndroidTestAppLauncher
import com.itsaky.androidide.quickbuild.ApkSigningCert
import com.itsaky.androidide.quickbuild.EnvironmentQuickBuildPaths
import com.itsaky.androidide.quickbuild.GradleQuickBuildProvisioner
import com.itsaky.androidide.quickbuild.InstallationEventFlow
import com.itsaky.androidide.quickbuild.PreferencesQuickBuildModeStore
import com.itsaky.androidide.utils.ApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.quickbuild.data.DaemonProcessClient
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.SameAppIdGuard
import org.appdevforall.cotg.quickbuild.service.DeployChannel
import org.appdevforall.cotg.quickbuild.service.DeploySender
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.QuickBuildModeStore
import org.appdevforall.cotg.quickbuild.service.QuickBuildProvisioner
import org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager
import org.appdevforall.cotg.quickbuild.service.SameAppIdModeController
import org.appdevforall.cotg.quickbuild.service.TestAppConnections
import org.appdevforall.cotg.quickbuild.service.TestAppInstaller
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
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

		single { EnvironmentQuickBuildPaths() }

		single<QuickBuildDaemon> {
			DaemonProcessClient(
				paths = get<EnvironmentQuickBuildPaths>(),
				scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
			)
		}

		single<DeploySender> { DeployChannel(get()) }

		single<InstalledPackages> { AndroidInstalledPackages(androidContext()) }

		single<QuickBuildModeStore> {
			PreferencesQuickBuildModeStore(
				context = androidContext(),
				projectPath = { runCatching { IProjectManager.getInstance().projectDirPath }.getOrNull() },
			)
		}

		// One guard instance for the whole graph: the controller mints the episode
		// token that the provisioner's install assertions consult.
		single { SameAppIdGuard() }

		single {
			val installedPackages = get<InstalledPackages>()
			SameAppIdModeController(
				store = get(),
				packages = installedPackages,
				guard = get(),
				metrics = get(),
				// Entry-time best effort: the suffix-mode sibling test app (if
				// installed) was signed by the same on-device debug keystore. The
				// provisioner re-verifies against the built APK before any install.
				cogoCertSha256 = { realAppId ->
					installedPackages.signingCertSha256(
						realAppId + SameAppIdGuard.TEST_APP_ID_SUFFIX,
					)
				},
				// A mode flip is a rebaseline boundary: stop any live session; the next
				// start provisions from scratch under the new package identity. Lazy
				// lookup avoids a construction cycle with the session manager.
				onModeChanged = {
					runCatching { GlobalContext.get().get<QuickBuildSessionManager>().restartSession() }
				},
			)
		}

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
			)
		}

		single<QuickBuildProvisioner> {
			val context = androidContext()
			GradleQuickBuildProvisioner(
				context = context,
				paths = get<EnvironmentQuickBuildPaths>(),
				installer = get<TestAppInstaller>(),
				packages = get(),
				modeStore = get(),
				guard = get(),
				metrics = get(),
				apkCertSha256 = { apk -> ApkSigningCert.sha256(context, apk) },
			)
		}

		single<QuickBuildMetricsSink> {
			AnalyticsQuickBuildMetricsSink(
				analytics = get(),
				projectPath = { IProjectManager.getInstance().projectDirPath },
			)
		}

		single {
			QuickBuildSessionManager(
				daemon = get(),
				deploy = get(),
				provisioner = get(),
				connections = get(),
				paths = get<EnvironmentQuickBuildPaths>(),
				modeStore = get(),
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
			)
		}
	}
