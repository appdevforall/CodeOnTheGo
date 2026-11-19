package com.itsaky.androidide.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.manager.core.LoadedPlugin
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.manager.services.DebuggerEndpointSource
import com.itsaky.androidide.plugins.manager.services.IdeTerminalServiceImpl
import com.itsaky.androidide.plugins.services.DebuggerEndpointService
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * CredentialProtectedApplicationLoader's plugin start when the SQLite debugger endpoint source cannot
 * be installed: the steps after it still run and plugins still load. BaseApplication backs the
 * preferences the plugin manager reads; IDEApplication would start the whole app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = BaseApplication::class)
class PluginSystemStartTest {
	private val app get() = RuntimeEnvironment.getApplication()

	@Before
	fun setUp() {
		reset()
		probePlugin(File(app.filesDir, "plugins/probe.cgp"))
	}

	@After
	fun tearDown() = reset()

	@Test
	fun `a source that cannot be built leaves the later setup steps and plugin loading in place`() {
		CredentialProtectedApplicationLoader.initializePluginSystem(app) { error("database unavailable") }

		assertLaterStepsRan()
		val service = checkNotNull(awaitProbe().context.services.get(DebuggerEndpointService::class.java))
		val thrown = offMainThread { assertThrows(IllegalStateException::class.java) { service.getEndpoints() } }
		assertThat(thrown).hasMessageThat().startsWith("The SQLite debugger endpoints are unavailable")
	}

	@Test
	fun `a different source already installed leaves the later setup steps and plugin loading in place`() {
		PluginManager.getInstance(app, Any(), QuietLogger).installDebuggerEndpointSource { emptyList() }

		CredentialProtectedApplicationLoader.initializePluginSystem(app) { DebuggerEndpointSource { emptyList() } }

		assertLaterStepsRan()
		awaitProbe()
	}

	/** The steps that come after the endpoint source: the terminal launcher and the inflation error handler. */
	private fun assertLaterStepsRan() {
		assertWithMessage("terminal session launcher").that(sessionLauncherField().get(null)).isNotNull()
		assertWithMessage("plugin inflation error handler").that(PluginFragmentHelper.onPluginInflationError).isNotNull()
	}

	/** Waits for the plugin loading that initializePluginSystem starts on a background coroutine. */
	private fun awaitProbe(): LoadedPlugin {
		val deadline = System.currentTimeMillis() + 20_000
		while (System.currentTimeMillis() < deadline) {
			PluginManager.getInstance()?.getLoadedPlugin("probe")?.let { return it }
			Thread.sleep(50)
		}
		val error = PluginManager.getInstance()?.getLoadError("probe")
		throw AssertionError("plugin probe was not loaded within 20 s (load error: $error)")
	}

	/** getEndpoints refuses the main thread, where Robolectric runs the test. */
	private fun <T> offMainThread(block: () -> T): T {
		val result = AtomicReference<Result<T>>()
		thread { result.set(runCatching(block)) }.join()
		return result.get().getOrThrow()
	}

	/** Forgets what a start leaves in process-wide state, so each test starts from none. */
	private fun reset() {
		PluginManager::class.java
			.getDeclaredField("INSTANCE")
			.apply { isAccessible = true }
			.set(null, null)
		CredentialProtectedApplicationLoader::class.java
			.getDeclaredField("pluginManager")
			.apply { isAccessible = true }
			.set(null, null)
		sessionLauncherField().set(null, null)
		PluginFragmentHelper.onPluginInflationError = null
	}

	private fun sessionLauncherField() = IdeTerminalServiceImpl::class.java.getDeclaredField("sessionLauncher").apply { isAccessible = true }

	/** Writes [file] as a plugin with the debugger.endpoints permission: Robolectric can't read a manifest from an APK. */
	private fun probePlugin(file: File) {
		file.parentFile?.mkdirs()
		ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("classes.dex")) }
		val info =
			PackageInfo().apply {
				packageName = "org.example.probe"
				applicationInfo =
					ApplicationInfo().apply {
						metaData =
							Bundle().apply {
								putString("plugin.id", "probe")
								putString("plugin.main_class", ProbePlugin::class.java.name)
								putString("plugin.permissions", "debugger.endpoints")
							}
					}
			}
		shadowOf(app.packageManager).setPackageArchiveInfo(file.absolutePath, info)
	}

	/** A plugin that loads and activates. Its class loader finds it through its parent, the test's. */
	class ProbePlugin : IPlugin {
		override fun initialize(context: PluginContext) = true

		override fun activate() = true

		override fun deactivate() = true

		override fun dispose() = Unit
	}

	private object QuietLogger : PluginLogger {
		override val pluginId = "test"

		override fun debug(message: String) = Unit

		override fun debug(
			message: String,
			error: Throwable,
		) = Unit

		override fun info(message: String) = Unit

		override fun info(
			message: String,
			error: Throwable,
		) = Unit

		override fun warn(message: String) = Unit

		override fun warn(
			message: String,
			error: Throwable,
		) = Unit

		override fun error(message: String) = Unit

		override fun error(
			message: String,
			error: Throwable,
		) = Unit
	}
}
