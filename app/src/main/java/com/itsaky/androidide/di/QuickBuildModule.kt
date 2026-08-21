package com.itsaky.androidide.di

import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.itsaky.androidide.analytics.quickbuild.AnalyticsQuickBuildMetricsSink
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.quickbuild.AndroidInstalledPackages
import com.itsaky.androidide.quickbuild.AndroidProxyAppLauncher
import com.itsaky.androidide.quickbuild.ApkSigningCert
import com.itsaky.androidide.quickbuild.CompositeQuickBuildMetricsSink
import com.itsaky.androidide.quickbuild.EnvironmentQuickBuildPaths
import com.itsaky.androidide.quickbuild.GenerateSourcesDeferral
import com.itsaky.androidide.quickbuild.GradleQuickBuildProvisioner
import com.itsaky.androidide.quickbuild.InstallationEventFlow
import com.itsaky.androidide.quickbuild.PreferencesQuickBuildHistoryStore
import com.itsaky.androidide.quickbuild.QuickBuildBenchHooks
import com.itsaky.androidide.quickbuild.QuickBuildOutputMetricsSink
import com.itsaky.androidide.quickbuild.QuickBuildOutputNarrator
import com.itsaky.androidide.utils.ApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.quickbuild.data.DaemonProcessClient
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.service.deploy.DeployChannel
import org.appdevforall.cotg.quickbuild.service.deploy.DeploySender
import org.appdevforall.cotg.quickbuild.service.deploy.ProxyAppConnections
import org.appdevforall.cotg.quickbuild.service.provision.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppInstaller
import org.appdevforall.cotg.quickbuild.service.provision.QuickBuildClobberCheck
import org.appdevforall.cotg.quickbuild.service.provision.QuickBuildProvisioner
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildHistoryStore
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildSessionManager
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
		single { ProxyAppConnections.INSTANCE }

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

		// Confirm-on-switch check: reads which build (Quick Build proxy app vs Standard Run)
		// currently occupies the real applicationId, so the UI can warn before a clobber.
		single { QuickBuildClobberCheck(get<InstalledPackages>()) }

		single {
			val context = androidContext()
			ProxyAppInstaller(
				packages = get(),
				// The exact call the Run button's install flow bottoms out in:
				// same PackageInstaller session params, same InstallationResultReceiver,
				// same MIUI intent fallback. Post-install launch is suppressed: the session
				// switches to the proxy app itself on provisioning success, so the generic
				// launch-after-install must not fire a duplicate launch on every install.
				launchInstall = { apk ->
					withContext(Dispatchers.Main) {
						ApkInstaller.installApk(context, apk, suppressPostInstallLaunch = true)
					}
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
				installer = get<ProxyAppInstaller>(),
				packages = get(),
				apkCertSha256 = { apk -> ApkSigningCert.sha256(context, apk) },
				// Quotes Gradle into Build Output when the proxy app build fails, and reports
				// tasks as they run so a ~90 s provision reads as progress rather than a hang.
				narrator = get<QuickBuildOutputNarrator>(),
			)
		}

		// Session-scoped Build Output narration (ADFA-4128): outlives the editor activity
		// on purpose, so a build the user backgrounded CoGo to watch is still logged.
		// Delivery ends in a view, hence Main.
		single {
			QuickBuildOutputNarrator(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
		}

		// Defers the resource-save generateSources() Gradle run while a Quick Build session is
		// live (see GenerateSourcesDeferral). Deliberately dependency-free: the save call sites
		// resolve it on every resource save, and pulling the session manager here would spawn
		// the whole Quick Build graph on a save that never touched the lightning bolt.
		single {
			GenerateSourcesDeferral(
				scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
				runBuild = { ProjectManagerImpl.getInstance().generateSources() },
			)
		}

		single<QuickBuildMetricsSink> {
			val analytics =
				AnalyticsQuickBuildMetricsSink(
					analytics = get(),
					projectPath = { IProjectManager.getInstance().projectDirPath },
					// Forwarded as a plain count so multi-module reads as moduleCount > 1
					// without a new event (ADFA-4128). Counts ALL Gradle subprojects via the public
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
			// The narration sink ships: per-build stage timings are what makes a slow save
			// readable in the Build Output pane.
			val narration = QuickBuildOutputMetricsSink(get<QuickBuildOutputNarrator>())
			// A debug build under the bench flag fans a JSON-lines file in too, so an
			// external run reads timings over adb; null in every other build.
			val sinks = listOfNotNull(analytics, narration, QuickBuildBenchHooks.metricsSink())
			CompositeQuickBuildMetricsSink(*sinks.toTypedArray())
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
				// dispatcher (see LiveReloadOrchestrator KDoc); a dedicated thread keeps
				// session work off Main and off the shared pools.
				dispatcher =
					Executors
						.newSingleThreadExecutor { runnable ->
							Thread(runnable, "QuickBuildSession")
						}.asCoroutineDispatcher(),
				metrics = get(),
				// Restart deploys (service/provider/Application code changed): the
				// runtime exits after persisting; this relaunches the launcher proxy.
				launcher = AndroidProxyAppLauncher(androidContext()),
				// Monotonic device clock for the e2e timing line (ADFA-4128); the module
				// default is JVM currentTimeMillis for unit tests.
				nowMillis = SystemClock::elapsedRealtime,
				// Bench A/B seam: CodeOnTheGo.qbnoseed suppresses the post-provisioning
				// background warm compile, but only in a debug build under the bench flag -
				// a release build always warm-compiles.
				warmCompileEnabled = QuickBuildBenchHooks::warmCompileEnabled,
				// The proxy app runtime serves deployed assets through a ResourcesLoader
				// AssetsProvider, which is API 30+. Below that an asset edit would be
				// extracted and never read, so those edits rebaseline instead.
				assetsLiveReloadable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
			).also { manager ->
				// Narration must be scoped to the session, not to an activity on screen:
				// an activity-scoped collector misses every generation produced while the
				// editor is not up.
				get<QuickBuildOutputNarrator>().attach(manager.status)
				// The resource-save deferral keys off the same state stream the status surfaces
				// read; attach is idempotent, so re-running this block cannot double-collect.
				get<GenerateSourcesDeferral>().attach(manager.state)
				// ADFA-4128 harness (debug + bench flag only): a second, read-only collector
				// on the existing state stream, writing one JSON line per state change. The
				// UI's own collector is untouched.
				QuickBuildBenchHooks.attachStateRecorder(manager.state)
			}
		}
	}
