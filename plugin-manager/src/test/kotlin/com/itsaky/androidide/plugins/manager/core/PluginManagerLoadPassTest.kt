package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * loadPlugins() runs at startup, after every install and when bundled plugins start, so a pass
 * meets plugins an earlier pass loaded. It leaves their files alone, so it keeps the activation
 * failures they reported, which nothing would report again. It forgets the failures of plugins no
 * longer loaded, and a plugin that activates clears its failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginManagerLoadPassTest {
	private val pluginsDir by lazy { File(RuntimeEnvironment.getApplication().filesDir, "plugins") }

	@Before
	fun resetPlugin() = CountingPlugin.reset()

	@After
	fun forgetManager() = forgetPluginManager()

	@Test
	fun aSecondPassDoesNotLoadOrActivateALoadedPluginAgain() {
		countingPlugin("a")
		load()
		val first = pluginManager().getPlugin("a")
		assertWithMessage("plugin a did not load: ${pluginManager().getLoadError("a")}").that(first).isNotNull()

		load()

		assertWithMessage("the second pass replaced plugin a").that(pluginManager().getPlugin("a")).isSameInstanceAs(first)
		assertWithMessage("the second pass initialized plugin a again").that(CountingPlugin.initializations.get()).isEqualTo(1)
		assertWithMessage("the second pass activated plugin a again").that(CountingPlugin.activations.get()).isEqualTo(1)
	}

	@Test
	fun aSecondPassDoesNotOpenAFileAlreadyLoaded() {
		val file = countingPlugin("a")
		load()
		assertThat(pluginManager().getLoadedPluginIds()).containsExactly("a")
		// Robolectric reads a plugin's manifest from the package manager: the loaded file now reads
		// as plugin "b", which only a pass that opened the file again would load.
		fakePlugin(file, "b", NoopPlugin::class.java.name)

		load()

		assertWithMessage("the second pass opened the loaded file again").that(pluginManager().getLoadedPluginIds()).containsExactly("a")
	}

	@Test
	fun aLoadedPluginsActivationFailureSurvivesALaterPass() {
		CountingPlugin.failActivation = true
		countingPlugin("a")
		load()
		assertThat(pluginManager().getLoadedPluginIds()).containsExactly("a")
		assertThat(pluginManager().getLoadError("a")).isEqualTo(CountingPlugin.ACTIVATION_ERROR)

		load()

		assertWithMessage("the later pass dropped the activation failure of loaded plugin a")
			.that(pluginManager().getLoadError("a"))
			.isEqualTo(CountingPlugin.ACTIVATION_ERROR)
	}

	@Test
	fun aPassDropsTheFailureOfAPluginNoLongerLoaded() {
		CountingPlugin.failActivation = true
		countingPlugin("a")
		load()
		assertThat(pluginManager().getLoadError("a")).isEqualTo(CountingPlugin.ACTIVATION_ERROR)
		assertThat(pluginManager().uninstallPlugin("a")).isTrue()
		// Its main class is missing, so the pass records a failure of its own, which it must report.
		fakePlugin(File(pluginsDir, "b-0123456789012345.cgp"), "b")

		load()

		assertWithMessage("the failure of uninstalled plugin a outlived the pass").that(pluginManager().getLoadError("a")).isNull()
		assertWithMessage("the pass dropped the failure it recorded itself").that(pluginManager().getLoadError("b")).isNotNull()
	}

	@Test
	fun aSuccessfulActivationClearsTheEarlierFailure() {
		CountingPlugin.failActivation = true
		countingPlugin("a")
		load()
		assertThat(pluginManager().getLoadError("a")).isEqualTo(CountingPlugin.ACTIVATION_ERROR)

		CountingPlugin.failActivation = false
		assertWithMessage("enabling plugin a failed").that(pluginManager().enablePlugin("a")).isTrue()

		assertWithMessage("plugin a activated, but its earlier failure is still reported").that(pluginManager().getLoadError("a")).isNull()
	}

	private fun load() = runBlocking { pluginManager().loadPlugins() }

	private fun countingPlugin(id: String) = fakePlugin(File(pluginsDir, "$id-0123456789012345.cgp"), id, CountingPlugin::class.java.name)

	/** Counts its initialize() and activate() calls. activate() throws while [failActivation] is set. */
	class CountingPlugin : IPlugin {
		override fun initialize(context: PluginContext): Boolean {
			initializations.incrementAndGet()
			return true
		}

		override fun activate(): Boolean {
			activations.incrementAndGet()
			check(!failActivation) { ACTIVATION_ERROR }
			return true
		}

		override fun deactivate() = true

		override fun dispose() = Unit

		companion object {
			const val ACTIVATION_ERROR = "activate() failed on purpose"
			val initializations = AtomicInteger()
			val activations = AtomicInteger()

			@Volatile
			var failActivation = false

			fun reset() {
				initializations.set(0)
				activations.set(0)
				failActivation = false
			}
		}
	}
}
