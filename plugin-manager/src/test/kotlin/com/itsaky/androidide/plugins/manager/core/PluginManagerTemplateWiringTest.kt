package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.utils.Environment
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The plugin manager tells template cleanup which other plugins are loaded: unloading or disabling
 * plugin "x" keeps the templates of the loaded plugin "x_tools", whose names start with the same prefix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginManagerTemplateWiringTest {
	private val previous = Environment.TEMPLATES_DIR
	private lateinit var templates: File
	private lateinit var own: File
	private lateinit var other: File

	@Before
	fun loadTwoPluginsWithTemplates() {
		templates = File(RuntimeEnvironment.getApplication().filesDir, "templates").apply { mkdirs() }
		Environment.TEMPLATES_DIR = templates
		own = template("plugin_x_starter.cgt")
		other = template("plugin_x_tools_starter.cgt")
		val pluginsDir = File(RuntimeEnvironment.getApplication().filesDir, "plugins")
		val main = NoopPlugin::class.java.name
		listOf("x", "x_tools").forEach {
			val result = pluginManager().loadPlugin(fakePlugin(File(pluginsDir, "$it-0123456789012345.cgp"), it, main))
			assertWithMessage("plugin $it did not load: ${result.exceptionOrNull()}").that(result.isSuccess).isTrue()
		}
		assertThat(pluginManager().getLoadedPluginIds()).containsExactly("x", "x_tools")
	}

	@After
	fun restoreTemplatesFolder() {
		Environment.TEMPLATES_DIR = previous
		forgetPluginManager()
	}

	@Test
	fun unloadingAPluginKeepsTheTemplatesOfAnotherLoadedPlugin() {
		assertThat(pluginManager().unloadPlugin("x")).isTrue()

		assertThat(own.exists()).isFalse()
		assertWithMessage("unloading \"x\" deleted a template of \"x_tools\"").that(other.exists()).isTrue()
	}

	@Test
	fun forceDisablingAPluginKeepsTheTemplatesOfAnotherLoadedPlugin() {
		pluginManager().forceDisablePlugin("x")

		assertThat(own.exists()).isFalse()
		assertWithMessage("disabling \"x\" deleted a template of \"x_tools\"").that(other.exists()).isTrue()
	}

	@Test
	fun aPluginsTemplateServiceDoesNotListTheTemplatesOfAnotherLoadedPlugin() {
		val plugin = pluginManager().getPlugin("x") as NoopPlugin
		val service = plugin.context.services.get(IdeTemplateService::class.java)!!

		assertThat(service.getRegisteredTemplates()).containsExactly("starter.cgt")
	}

	private fun template(name: String): File = File(templates, name).apply { writeText(name) }
}
