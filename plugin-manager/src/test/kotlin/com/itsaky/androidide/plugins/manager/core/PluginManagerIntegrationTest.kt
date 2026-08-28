package com.itsaky.androidide.plugins.manager.core

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.manager.context.ServiceRegistryImpl
import com.itsaky.androidide.plugins.manager.services.IdeFileServiceImpl
import com.itsaky.androidide.plugins.manager.services.IdeProjectServiceImpl
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests for plugin service registration and retrieval, mirroring how
 * PluginManager registers services into the [ServiceRegistry] and how plugins then
 * resolve and use them.
 */
class PluginManagerIntegrationTest {
	private lateinit var tempProjectRoot: File
	private lateinit var serviceRegistry: ServiceRegistry

	@Before
	fun setup() {
		tempProjectRoot =
			File.createTempFile("test_project_", "").apply {
				delete()
				mkdirs()
			}
		File(tempProjectRoot, "test.txt").writeText("Integration test content")
		serviceRegistry = ServiceRegistryImpl()
	}

	@After
	fun cleanup() {
		tempProjectRoot.deleteRecursively()
	}

	@Test
	fun servicesCanBeRegisteredAndRetrieved() {
		val fileService = fileService()
		serviceRegistry.register(IdeFileService::class.java, fileService)

		val projectService =
			IdeProjectServiceImpl(
				pluginId = "test-plugin",
				permissions = setOf(PluginPermission.FILESYSTEM_READ),
				projectProvider = EmptyProjectProvider,
			)
		serviceRegistry.register(IdeProjectService::class.java, projectService)

		val resolvedFile = serviceRegistry.get(IdeFileService::class.java)
		assertNotNull("IdeFileService should be registered and retrievable", resolvedFile)
		assertSame("Retrieved service should be the same instance", fileService, resolvedFile)

		val resolvedProject = serviceRegistry.get(IdeProjectService::class.java)
		assertNotNull("IdeProjectService should be registered and retrievable", resolvedProject)
		assertSame("Retrieved service should be the same instance", projectService, resolvedProject)
	}

	@Test
	fun fileServiceIsFunctionalThroughRegistry() {
		serviceRegistry.register(IdeFileService::class.java, fileService())
		val service = serviceRegistry.get(IdeFileService::class.java)
		assertNotNull("Service should be retrievable from registry", service)

		val existing = File(tempProjectRoot, "test.txt")
		assertEquals("Integration test content", service!!.readFile(existing))

		val created = File(tempProjectRoot, "new_file.txt")
		assertTrue("write should succeed", service.writeFile(created, "New content"))
		assertEquals("New content", created.readText())

		assertTrue("replace should succeed", service.replaceInFile(existing, "Integration", "Updated"))
		assertEquals("Updated test content", existing.readText())

		val names = service.listFiles(tempProjectRoot, recursive = false).map { it.name }
		assertTrue("listing should contain test.txt", names.contains("test.txt"))
		assertTrue("listing should contain new_file.txt", names.contains("new_file.txt"))

		assertTrue("delete should succeed", service.delete(created))
		assertFalse("deleted file should be gone", created.exists())
	}

	@Test(expected = SecurityException::class)
	fun fileServiceRejectsPathsOutsideProject() {
		serviceRegistry.register(IdeFileService::class.java, fileService())
		serviceRegistry.get(IdeFileService::class.java)!!.readFile(File("/etc/passwd"))
	}

	private fun fileService() =
		IdeFileServiceImpl(
			pluginId = "test-plugin",
			permissions = setOf(PluginPermission.FILESYSTEM_WRITE),
			pathValidator =
				object : IdeFileServiceImpl.PathValidator {
					override fun isPathAllowed(path: File) = path.isWithin(tempProjectRoot)

					override fun getAllowedPaths() = listOf(tempProjectRoot.absolutePath)
				},
		)

	private object EmptyProjectProvider : IdeProjectServiceImpl.ProjectProvider {
		override fun getCurrentProject(): IProject? = null

		override fun getAllProjects(): List<IProject> = emptyList()

		override fun getProjectByPath(path: File): IProject? = null
	}

	// Canonical, component-wise containment: rejects sibling-prefix and traversal escapes that a
	// naive absolutePath.startsWith() would wrongly accept.
	private fun File.isWithin(root: File) = canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
}
