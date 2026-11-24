package com.itsaky.androidide.plugins.manager.core

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.manager.documentation.PluginDocumentationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * A plugin built against a newer plugin API, loaded by an older CoGo, throws NoClassDefFoundError
 * when it first touches a missing class (GlitchTip CODEONTHEGO-10B, ADFA-6672). It must fail that
 * plugin, not crash the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PluginLinkageErrorTest {
	@Test
	fun initializeThrowingNoClassDefFoundErrorFailsThePlugin() {
		val result = initializePlugin(NewerApiPlugin(), stubContext())

		assertTrue(result.exceptionOrNull() is NoClassDefFoundError)
	}

	@Test
	fun documentationUninstallThrowingNoClassDefFoundErrorDoesNotEscape() {
		val manager = PluginDocumentationManager(RuntimeEnvironment.getApplication())

		val removed = runBlocking { manager.removePluginDocumentation("newer-api", NewerApiPlugin()) }

		assertFalse(removed)
	}

	private fun stubContext(): PluginContext =
		Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PluginContext::class.java)) { _, _, _ ->
			null
		} as PluginContext

	private class NewerApiPlugin : DocumentationExtension {
		private fun missingClass(): Nothing =
			throw NoClassDefFoundError("Failed resolution of: Lcom/itsaky/androidide/plugins/ai/LlmBackendRegistration;")

		override fun initialize(context: PluginContext): Boolean = missingClass()

		override fun onDocumentationUninstall() = missingClass()

		override fun activate() = true

		override fun deactivate() = true

		override fun dispose() {}

		override fun getTooltipCategory() = "newer-api"

		override fun getTooltipEntries() = emptyList<PluginTooltipEntry>()
	}
}
