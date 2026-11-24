package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.plugins.PluginPermission
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
 * Plugins register their templates on every activation (ADFA-7287). Registering an unchanged
 * template must leave the registered copy alone and not reload the template list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdeTemplateServiceImplRegisterTest {
	private val previous = Environment.TEMPLATES_DIR
	private lateinit var templates: File
	private lateinit var pluginDir: File
	private var reloads = 0

	@Before
	fun pointTemplatesAtATempFolder() {
		val files = RuntimeEnvironment.getApplication().filesDir
		templates = File(files, "templates").apply { mkdirs() }
		pluginDir = File(files, "plugin").apply { mkdirs() }
		Environment.TEMPLATES_DIR = templates
	}

	@After
	fun restoreTemplatesFolder() {
		Environment.TEMPLATES_DIR = previous
	}

	@Test
	fun registeringAnUnchangedTemplateLeavesTheCopyAlone() {
		val cgt = File(pluginDir, "Starter.cgt").apply { writeText("v1") }
		val service = service()
		assertThat(service.registerTemplate(cgt)).isTrue()
		val copy = File(templates, "plugin_com.example.my_Starter.cgt")
		val old = 1_000_000_000_000L
		assertThat(copy.setLastModified(old)).isTrue()

		assertThat(service.registerTemplate(cgt)).isTrue()

		assertWithMessage("the identical copy was written again").that(copy.lastModified()).isEqualTo(old)
		assertWithMessage("template list reloads").that(reloads).isEqualTo(1)
	}

	@Test
	fun registeringAChangedTemplateReplacesTheCopy() {
		val cgt = File(pluginDir, "Starter.cgt").apply { writeText("v1") }
		val service = service()
		service.registerTemplate(cgt)

		cgt.writeText("v2")
		assertThat(service.registerTemplate(cgt)).isTrue()

		assertThat(File(templates, "plugin_com.example.my_Starter.cgt").readText()).isEqualTo("v2")
		assertWithMessage("template list reloads").that(reloads).isEqualTo(2)
	}

	@Test
	fun registeringADeletedCopyAgainRestoresIt() {
		val cgt = File(pluginDir, "Starter.cgt").apply { writeText("v1") }
		val service = service()
		service.registerTemplate(cgt)
		service.cleanupAllTemplates()

		assertThat(service.registerTemplate(cgt)).isTrue()

		assertThat(File(templates, "plugin_com.example.my_Starter.cgt").readText()).isEqualTo("v1")
	}

	private fun service() =
		IdeTemplateServiceImpl(
			"com.example.my",
			setOf(PluginPermission.FILESYSTEM_WRITE),
			{ reloads++ },
			{ listOf("com.example.my") },
		)
}
