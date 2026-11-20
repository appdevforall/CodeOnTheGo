package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.manager.project.PluginProjectManager
import com.itsaky.androidide.plugins.manager.services.IdeTemplateServiceImpl
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
 * Updating or uninstalling plugin "com.example.my" deletes its own templates, not those of the
 * loaded plugin "com.example.my_tools", whose names start with the same prefix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginTemplateCleanupTest {
	private val previous = Environment.TEMPLATES_DIR
	private lateinit var templates: File

	@Before
	fun pointTemplatesAtATempFolder() {
		templates = File(RuntimeEnvironment.getApplication().filesDir, "templates").apply { mkdirs() }
		Environment.TEMPLATES_DIR = templates
	}

	@After
	fun restoreTemplatesFolder() {
		Environment.TEMPLATES_DIR = previous
	}

	@Test
	fun cleanupPluginTemplatesKeepsAnotherLoadedPluginsTemplates() {
		val own = template("plugin_com.example.my_starter.cgt")
		val other = template("plugin_com.example.my_tools_starter.cgt")

		PluginProjectManager.getInstance().cleanupPluginTemplates("com.example.my", listOf("com.example.my_tools"))

		assertThat(own.exists()).isFalse()
		assertWithMessage("cleanup deleted another plugin's template").that(other.exists()).isTrue()
	}

	@Test
	fun cleanupAllTemplatesKeepsAnotherLoadedPluginsTemplates() {
		val own = template("plugin_com.example.my_starter.cgt")
		val other = template("plugin_com.example.my_tools_starter.cgt")
		val service = service("com.example.my", loaded = listOf("com.example.my", "com.example.my_tools"))

		service.cleanupAllTemplates()

		assertThat(own.exists()).isFalse()
		assertWithMessage("cleanup deleted another plugin's template").that(other.exists()).isTrue()
	}

	@Test
	fun getRegisteredTemplatesListsOnlyTheOwnTemplates() {
		template("plugin_com.example.my_starter.cgt")
		template("plugin_com.example.my_tools_starter.cgt")
		val service = service("com.example.my", loaded = listOf("com.example.my", "com.example.my_tools"))

		assertThat(service.getRegisteredTemplates()).containsExactly("starter.cgt")
	}

	private fun service(
		id: String,
		loaded: List<String>,
	) = IdeTemplateServiceImpl(id, setOf(PluginPermission.FILESYSTEM_WRITE), {}, { loaded })

	private fun template(name: String): File = File(templates, name).apply { writeText(name) }
}
