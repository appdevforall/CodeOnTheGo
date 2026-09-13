package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The plugin-facing contract of `IdeEditorService.saveFile`: an authorization failure throws
 * rather than returning `false`, so a plugin can tell "denied" from "the write did not land".
 */
class IdeEditorServiceImplSaveFileTest {
	private lateinit var projectRoot: File
	private lateinit var provider: RecordingProvider

	@Before
	fun setUp() {
		projectRoot =
			File.createTempFile("ide-editor-service-", "").apply {
				delete()
				mkdirs()
			}
		provider = RecordingProvider()
	}

	@After
	fun tearDown() {
		projectRoot.deleteRecursively()
	}

	@Test
	fun saveFileDelegatesToTheProviderForAnAllowedFile() {
		val file = File(projectRoot, "Main.kt")
		provider.result = true

		assertTrue(runBlocking { service().saveFile(file) })
		assertEquals(file, provider.saved)
	}

	@Test
	fun saveFileReportsTheProvidersFailureVerbatim() {
		provider.result = false

		assertFalse(runBlocking { service().saveFile(File(projectRoot, "Main.kt")) })
	}

	@Test(expected = SecurityException::class)
	fun saveFileWithoutWritePermissionThrowsSecurityException() {
		runBlocking { service(permissions = emptySet()).saveFile(File(projectRoot, "Main.kt")) }
	}

	@Test(expected = SecurityException::class)
	fun saveFileOutsideTheAllowedRootsThrowsSecurityException() {
		runBlocking { service().saveFile(File("/etc/hosts")) }
	}

	@Test
	fun saveFileDeniedByPermissionNeverReachesTheProvider() {
		runCatching { runBlocking { service(permissions = emptySet()).saveFile(File(projectRoot, "Main.kt")) } }

		assertNull(provider.saved)
	}

	// The production allowlist only permits fixed on-device roots, so a test rooted at a temp
	// dir must supply its own validator that permits paths under it.
	private fun service(permissions: Set<PluginPermission> = setOf(PluginPermission.FILESYSTEM_WRITE)) =
		IdeEditorServiceImpl(
			pluginId = "test-plugin",
			permissions = permissions,
			editorProvider = provider,
			pathValidator =
				object : IdeEditorServiceImpl.PathValidator {
					override fun isPathAllowed(file: File) = file.canonicalFile.toPath().startsWith(projectRoot.canonicalFile.toPath())

					override fun getAllowedPaths() = listOf(projectRoot.absolutePath)
				},
		)

	private class RecordingProvider : IdeEditorServiceImpl.EditorProvider {
		var result = false
		var saved: File? = null

		override fun getCurrentFile(): File? = null

		override fun getOpenFiles(): List<File> = emptyList()

		override fun isFileOpen(file: File): Boolean = false

		override fun getCurrentSelection(): String? = null

		override suspend fun saveFile(file: File): Boolean {
			saved = file
			return result
		}
	}
}
