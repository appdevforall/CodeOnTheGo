package com.itsaky.androidide.plugins.manager.core

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.manager.services.DebuggerEndpointSource
import com.itsaky.androidide.plugins.manager.services.IdeEditorServiceImpl
import com.itsaky.androidide.plugins.services.DebuggerEndpoint
import com.itsaky.androidide.plugins.services.DebuggerEndpointService
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.utils.Environment
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
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * The [DebuggerEndpointService] a plugin gets when [PluginManager] loads it, through the registration
 * the app runs (createPluginContextWithResources). BaseApplication backs GeneralPreferences, which
 * remembers the last opened project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = BaseApplication::class)
class PluginManagerDebuggerEndpointTest {
	private lateinit var project: File
	private val endpoint = DebuggerEndpoint("com.example.demo", 4321, "tok", 99L)
	private val asked = CopyOnWriteArrayList<String>()

	private val templatesDir = Environment.TEMPLATES_DIR

	@Before
	fun setUp() {
		project = Files.createTempDirectory("proj").toFile()
		// Disabling and unloading clean up the plugin's templates there.
		Environment.TEMPLATES_DIR = File(RuntimeEnvironment.getApplication().filesDir, "templates").apply { mkdirs() }
		pluginManager().installDebuggerEndpointSource { location ->
			asked += location
			if (location == project.absolutePath) listOf(endpoint) else emptyList()
		}
	}

	@After
	fun tearDown() {
		forgetPluginManager()
		Environment.TEMPLATES_DIR = templatesDir
		ProjectManagerImpl.getInstance().projectPath = ""
		GeneralPreferences.lastOpenedProject = GeneralPreferences.NO_OPENED_PROJECT
		project.deleteRecursively()
	}

	@Test
	fun `the endpoints of the project open in the editor are returned`() {
		val service = load("reader", "debugger.endpoints")
		openInEditor(project)

		assertThat(offMainThread { service.getEndpoints() }).containsExactly(endpoint)
	}

	@Test
	fun `a closed project's endpoints are not returned, though it is still the last opened project`() {
		val service = load("reader", "debugger.endpoints")
		openInEditor(project)
		pluginManager().setEditorProvider(null)

		assertThat(offMainThread { service.getEndpoints() }).isEmpty()
		assertThat(asked).isEmpty()
	}

	@Test
	fun `each loaded plugin gets a service bound to its own permissions and the project now open`() {
		val reader = load("reader", "debugger.endpoints")
		val other = load("other", "filesystem.read,network.access")
		val elsewhere = Files.createTempDirectory("other").toFile()
		try {
			openInEditor(project)

			assertThat(offMainThread { reader.getEndpoints() }).containsExactly(endpoint)
			assertThrows(SecurityException::class.java) { offMainThread { other.getEndpoints() } }

			openInEditor(elsewhere)

			assertThat(offMainThread { reader.getEndpoints() }).isEmpty()
			assertThat(asked).containsExactly(project.absolutePath, elsewhere.absolutePath).inOrder()
		} finally {
			elsewhere.deleteRecursively()
		}
	}

	@Test
	fun `a disabled plugin's service refuses until the plugin is enabled again`() {
		val service = load("reader", "debugger.endpoints")
		openInEditor(project)

		assertThat(pluginManager().disablePlugin("reader")).isTrue()

		assertThrows(CancellationException::class.java) { offMainThread { service.getEndpoints() } }

		assertThat(pluginManager().enablePlugin("reader")).isTrue()

		assertThat(offMainThread { service.getEndpoints() }).containsExactly(endpoint)
	}

	@Test
	fun `a plugin disabled after crashing gets nothing`() {
		val service = load("reader", "debugger.endpoints")
		openInEditor(project)

		pluginManager().forceDisablePlugin("reader")

		assertThrows(CancellationException::class.java) { offMainThread { service.getEndpoints() } }
		assertThat(asked).isEmpty()
	}

	@Test
	fun `an unloaded plugin's service refuses`() {
		val service = load("reader", "debugger.endpoints")
		openInEditor(project)

		assertThat(pluginManager().unloadPlugin("reader")).isTrue()

		assertThrows(CancellationException::class.java) { offMainThread { service.getEndpoints() } }
		assertThat(asked).isEmpty()
	}

	@Test
	fun `a plugin loaded while disabled gets a service that refuses`() {
		load("reader", "debugger.endpoints")
		assertThat(pluginManager().disablePlugin("reader")).isTrue()
		assertThat(pluginManager().unloadPlugin("reader")).isTrue()
		openInEditor(project)

		val reloaded = load("reader", "debugger.endpoints")

		assertThat(pluginManager().getLoadedPlugin("reader")).isNull()
		assertThrows(CancellationException::class.java) { offMainThread { reloaded.getEndpoints() } }
	}

	@Test
	fun `a plugin saved as disabled cannot read endpoints from its own initialize`() {
		load("reader", "debugger.endpoints")
		assertThat(pluginManager().disablePlugin("reader")).isTrue()
		assertThat(pluginManager().unloadPlugin("reader")).isTrue()
		openInEditor(project)
		ReadingInInitializePlugin.outcome = null

		load("reader", "debugger.endpoints", ReadingInInitializePlugin::class.java)

		assertThat(ReadingInInitializePlugin.outcome!!.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
		assertThat(asked).isEmpty()
	}

	@Test
	fun `an enabled plugin can read endpoints from its own initialize`() {
		openInEditor(project)
		ReadingInInitializePlugin.outcome = null

		load("reader", "debugger.endpoints", ReadingInInitializePlugin::class.java)

		assertThat(ReadingInInitializePlugin.outcome!!.getOrThrow()).containsExactly(endpoint)
	}

	@Test
	fun `a plugin whose initialize returns false or throws is left with a service that refuses`() {
		openInEditor(project)
		for (mode in listOf(FailingInitializePlugin.Mode.RETURN_FALSE, FailingInitializePlugin.Mode.THROW)) {
			FailingInitializePlugin.mode = mode
			FailingInitializePlugin.service = null
			val file = fakePlugin(File(RuntimeEnvironment.getApplication().filesDir, "plugins/failing.cgp"), "failing", "debugger.endpoints", FailingInitializePlugin::class.java)

			assertThat(pluginManager().loadPlugin(file).isFailure).isTrue()

			val service = checkNotNull(FailingInitializePlugin.service)
			assertThrows(CancellationException::class.java) { offMainThread { service.getEndpoints() } }
		}
		assertThat(asked).isEmpty()
	}

	@Test
	fun `a plugin whose activation fails is left disabled and its service refuses`() {
		openInEditor(project)

		val service = load("reader", "debugger.endpoints", FailingActivationPlugin::class.java)

		assertThat(pluginManager().getLoadedPlugin("reader")).isNull()
		assertThrows(CancellationException::class.java) { offMainThread { service.getEndpoints() } }
		assertThat(asked).isEmpty()
	}

	@Test
	fun `a second install, as a plugin would try, fails and leaves the app's source in place`() {
		val service = load("reader", "debugger.endpoints")
		openInEditor(project)

		assertThrows(IllegalStateException::class.java) { pluginManager().installDebuggerEndpointSource { emptyList() } }

		assertThat(offMainThread { service.getEndpoints() }).containsExactly(endpoint)
	}

	@Test
	fun `no public member hands out the source, and the public holder plugins could read is gone`() {
		val types = PluginManager::class.java.methods.map { it.returnType } + PluginManager::class.java.fields.map { it.type }

		assertThat(types.filter { DebuggerEndpointSource::class.java.isAssignableFrom(it) }).isEmpty()
		assertThrows(ClassNotFoundException::class.java) {
			Class.forName("com.itsaky.androidide.plugins.manager.services.DebuggerEndpointSources")
		}
	}

	/** What opening [dir] leaves behind: the project path and the last opened project, and an editor showing it. */
	private fun openInEditor(dir: File) {
		ProjectManagerImpl.getInstance().projectPath = dir.absolutePath
		GeneralPreferences.lastOpenedProject = dir.absolutePath
		pluginManager().setEditorProvider(OpenEditor)
	}

	private fun load(
		id: String,
		permissions: String,
		mainClass: Class<out IPlugin> = ContextKeepingPlugin::class.java,
	): DebuggerEndpointService {
		val file = fakePlugin(File(RuntimeEnvironment.getApplication().filesDir, "plugins/$id.cgp"), id, permissions, mainClass)
		val result = pluginManager().loadPlugin(file)
		assertWithMessage("plugin $id did not load: ${result.exceptionOrNull()}").that(result.isSuccess).isTrue()
		val plugin = pluginManager().getPlugin(id) as ContextKeepingPlugin
		return checkNotNull(plugin.context.services.get(DebuggerEndpointService::class.java)) { "no DebuggerEndpointService for $id" }
	}

	/** Runs [block] on a worker thread: on the main thread, where Robolectric runs the test, getEndpoints throws. */
	private fun <T> offMainThread(block: () -> T): T {
		val result = AtomicReference<Result<T>>()
		thread { result.set(runCatching(block)) }.join()
		return result.get().getOrThrow()
	}

	private fun pluginManager() = PluginManager.getInstance(RuntimeEnvironment.getApplication(), Any(), NoLogger)

	/** Forgets the singleton plugin manager: each test has an application of its own. */
	private fun forgetPluginManager() {
		PluginManager::class.java
			.getDeclaredField("INSTANCE")
			.apply { isAccessible = true }
			.set(null, null)
	}

	/** Writes [file] as a plugin whose manifest states [id] and [permissions]: Robolectric can't read one from an APK. */
	private fun fakePlugin(
		file: File,
		id: String,
		permissions: String,
		mainClass: Class<out IPlugin> = ContextKeepingPlugin::class.java,
	): File {
		file.parentFile?.mkdirs()
		ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("classes.dex")) }
		val info =
			PackageInfo().apply {
				packageName = "org.example.$id"
				applicationInfo =
					ApplicationInfo().apply {
						metaData =
							Bundle().apply {
								putString("plugin.id", id)
								putString("plugin.main_class", mainClass.name)
								putString("plugin.permissions", permissions)
							}
					}
			}
		shadowOf(RuntimeEnvironment.getApplication().packageManager).setPackageArchiveInfo(file.absolutePath, info)
		return file
	}

	/** A plugin that keeps its context. Its class loader finds it through its parent, the test's. */
	open class ContextKeepingPlugin : IPlugin {
		lateinit var context: PluginContext

		override fun initialize(context: PluginContext): Boolean {
			this.context = context
			return true
		}

		override fun activate() = true

		override fun deactivate() = true

		override fun dispose() = Unit
	}

	/** Reads endpoints from initialize(), on a thread of its own as a real plugin would. */
	class ReadingInInitializePlugin : ContextKeepingPlugin() {
		override fun initialize(context: PluginContext): Boolean {
			super.initialize(context)
			val service = checkNotNull(context.services.get(DebuggerEndpointService::class.java))
			val result = AtomicReference<Result<List<DebuggerEndpoint>>>()
			thread { result.set(runCatching { service.getEndpoints() }) }.join()
			outcome = result.get()
			return true
		}

		companion object {
			@Volatile
			var outcome: Result<List<DebuggerEndpoint>>? = null
		}
	}

	/** Keeps its service, then fails initialize() as [mode] says. */
	class FailingInitializePlugin : ContextKeepingPlugin() {
		enum class Mode { RETURN_FALSE, THROW }

		override fun initialize(context: PluginContext): Boolean {
			service = context.services.get(DebuggerEndpointService::class.java)
			if (mode == Mode.THROW) error("initialize failed")
			return false
		}

		companion object {
			@Volatile
			var mode = Mode.RETURN_FALSE

			@Volatile
			var service: DebuggerEndpointService? = null
		}
	}

	/** initialize() succeeds, activate() throws. */
	class FailingActivationPlugin : ContextKeepingPlugin() {
		override fun activate(): Boolean = error("activate failed")
	}

	/** The editor activity's provider, as registered while it shows a project. */
	private object OpenEditor : IdeEditorServiceImpl.EditorProvider {
		override fun getCurrentFile(): File? = null

		override fun getOpenFiles(): List<File> = emptyList()

		override fun isFileOpen(file: File): Boolean = false

		override fun getCurrentSelection(): String? = null
	}

	private object NoLogger : PluginLogger {
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
