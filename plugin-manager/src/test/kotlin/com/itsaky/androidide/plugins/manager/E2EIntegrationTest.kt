package com.itsaky.androidide.plugins.manager

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.manager.context.ServiceRegistryImpl
import com.itsaky.androidide.plugins.manager.services.IdeFileServiceImpl
import com.itsaky.androidide.plugins.services.IdeFileService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class E2EIntegrationTest {
	private lateinit var tempProjectRoot: File

	@Before
	fun setUp() {
		tempProjectRoot =
			File.createTempFile("e2e-test-", "").apply {
				delete()
				mkdirs()
			}
	}

	@After
	fun tearDown() {
		tempProjectRoot.deleteRecursively()
	}

	@Test
	fun fileServiceWorkflowThroughRegistry() {
		val registry = ServiceRegistryImpl()
		val fileService =
			IdeFileServiceImpl(
				pluginId = "e2e-plugin",
				permissions = setOf(PluginPermission.FILESYSTEM_WRITE),
				pathValidator =
					object : IdeFileServiceImpl.PathValidator {
						override fun isPathAllowed(path: File) = path.absolutePath.startsWith(tempProjectRoot.absolutePath)

						override fun getAllowedPaths() = listOf(tempProjectRoot.absolutePath)
					},
			)
		registry.register(IdeFileService::class.java, fileService)

		val resolved = registry.get(IdeFileService::class.java)
		assertNotNull("IdeFileService should be retrievable from the registry", resolved)

		val sourceFile = File(tempProjectRoot, "app/src/main/kotlin/com/example/Main.kt")
		val source = "package com.example\n\nfun main() {\n    println(\"Hello\")\n}"

		assertTrue("write should succeed", resolved!!.writeFile(sourceFile, source))
		assertEquals(source, resolved.readFile(sourceFile))

		val listed = resolved.listFiles(sourceFile.parentFile, recursive = false).map { it.name }
		assertTrue("listing should include the created file", listed.contains("Main.kt"))

		assertTrue("delete should succeed", resolved.delete(sourceFile))
		assertFalse("deleted file should be gone", sourceFile.exists())
	}
}
