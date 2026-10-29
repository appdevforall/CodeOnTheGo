package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.IProject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class IdeProjectServiceImplModuleContextTest {
	private val noProjects =
		object : IdeProjectServiceImpl.ProjectProvider {
			override fun getCurrentProject(): IProject? = null

			override fun getAllProjects(): List<IProject> = emptyList()

			override fun getProjectByPath(path: File): IProject? = null
		}

	private val denyAllPaths =
		object : IdeProjectServiceImpl.PathValidator {
			override fun isPathAllowed(path: File) = false

			override fun getAllowedPaths() = emptyList<String>()
		}

	private fun service(permissions: Set<PluginPermission>) =
		IdeProjectServiceImpl(
			pluginId = "test-plugin",
			permissions = permissions,
			projectProvider = noProjects,
			pathValidator = denyAllPaths,
		)

	@Test
	fun getModuleContextRequiresFilesystemReadPermission() {
		assertThrows(SecurityException::class.java) {
			service(permissions = emptySet()).getModuleContext("/sdcard/CodeOnTheGoProjects/App/Main.kt")
		}
	}

	@Test
	fun getModuleContextRejectsPathsOutsideTheAllowlist() {
		assertThrows(SecurityException::class.java) {
			service(permissions = setOf(PluginPermission.FILESYSTEM_READ)).getModuleContext("/data/secret/Main.kt")
		}
	}
}
