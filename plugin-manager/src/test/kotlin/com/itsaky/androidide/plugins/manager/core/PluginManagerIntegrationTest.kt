package com.itsaky.androidide.plugins.manager.core

import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.manager.context.ServiceRegistryImpl
import com.itsaky.androidide.plugins.manager.services.FileServiceImpl
import com.itsaky.androidide.plugins.manager.services.ProjectServiceImpl
import com.itsaky.androidide.plugins.manager.services.ResourceServiceImpl
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeResourceService
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import java.io.File

/**
 * Integration tests for PluginManager service registration and retrieval.
 * These tests verify that services are properly registered and can be retrieved
 * through the service registry, mimicking how PluginManager registers services.
 */
class PluginManagerIntegrationTest {

    private lateinit var tempProjectRoot: File
    private lateinit var serviceRegistry: ServiceRegistry

    @Before
    fun setup() {
        // Create a temporary project root directory
        tempProjectRoot = File.createTempFile("test_project_", "").apply {
            delete()
            mkdirs()
        }

        // Create a build.gradle file for project service tests
        val buildFile = File(tempProjectRoot, "build.gradle")
        buildFile.writeText("""
            plugins {
                id 'java'
            }

            dependencies {
            }
        """.trimIndent())

        // Create a test file with content
        val testFile = File(tempProjectRoot, "test.txt")
        testFile.writeText("Integration test content")

        // Initialize service registry
        serviceRegistry = ServiceRegistryImpl()
    }

    @After
    fun cleanup() {
        // Clean up temporary directory
        if (tempProjectRoot.exists()) {
            tempProjectRoot.deleteRecursively()
        }
    }

    /**
     * Test that services can be registered and retrieved through the service registry.
     * This simulates how PluginManager registers services in createPluginContext()
     * (lines 1292-1323 of PluginManager.kt).
     */
    @Test
    fun testServiceRegistration() {
        // Simulate PluginManager's service registration for Phase 2 services
        // This mirrors the code in PluginManager.createPluginContext() lines 1292-1323

        // Register IdeFileService
        val fileService = FileServiceImpl(tempProjectRoot)
        serviceRegistry.register(IdeFileService::class.java, fileService)

        // Register IdeProjectService
        val projectService = ProjectServiceImpl(tempProjectRoot)
        serviceRegistry.register(IdeProjectService::class.java, projectService)

        // Register IdeResourceService
        val resourceService = ResourceServiceImpl(tempProjectRoot)
        serviceRegistry.register(IdeResourceService::class.java, resourceService)

        // Verify services can be retrieved through the registry
        val retrievedFileService = serviceRegistry.get(IdeFileService::class.java)
        assertNotNull("IdeFileService should be registered and retrievable", retrievedFileService)
        assertSame("Retrieved service should be the same instance", fileService, retrievedFileService)

        val retrievedProjectService = serviceRegistry.get(IdeProjectService::class.java)
        assertNotNull("IdeProjectService should be registered and retrievable", retrievedProjectService)
        assertSame("Retrieved service should be the same instance", projectService, retrievedProjectService)

        val retrievedResourceService = serviceRegistry.get(IdeResourceService::class.java)
        assertNotNull("IdeResourceService should be registered and retrievable", retrievedResourceService)
        assertSame("Retrieved service should be the same instance", resourceService, retrievedResourceService)
    }

    /**
     * Test that FileService retrieved through the service registry is functional.
     * This verifies the complete integration: registration -> retrieval -> usage.
     */
    @Test
    fun testFileServiceIntegration() {
        // Register the service (simulating PluginManager)
        val fileService = FileServiceImpl(tempProjectRoot)
        serviceRegistry.register(IdeFileService::class.java, fileService)

        // Retrieve service through the registry (how plugins would access it)
        val retrievedService = serviceRegistry.get(IdeFileService::class.java)
        assertNotNull("Service should be retrievable from registry", retrievedService)

        // Test basic file operations through the retrieved service

        // 1. Read existing file
        val readResult = retrievedService!!.readFile("test.txt")
        assertTrue("File read should succeed", readResult.success)
        assertEquals("Integration test content", readResult.data)

        // 2. Create new file
        val createResult = retrievedService.createFile("new_file.txt", "New content")
        assertTrue("File creation should succeed", createResult.success)
        val newFile = File(tempProjectRoot, "new_file.txt")
        assertTrue("New file should exist on disk", newFile.exists())
        assertEquals("New content", newFile.readText())

        // 3. Update existing file
        val updateResult = retrievedService.updateFile("test.txt", "Updated content")
        assertTrue("File update should succeed", updateResult.success)
        val testFile = File(tempProjectRoot, "test.txt")
        assertEquals("Updated content", testFile.readText())

        // 4. List files
        val listResult = retrievedService.listFiles(".", false)
        assertTrue("File listing should succeed", listResult.success)
        assertNotNull("File list should not be null", listResult.data)
        val fileList = listResult.data!!
        assertTrue("File list should contain test.txt", fileList.contains("test.txt"))
        assertTrue("File list should contain new_file.txt", fileList.contains("new_file.txt"))

        // 5. Delete file
        val deleteResult = retrievedService.deleteFile("new_file.txt")
        assertTrue("File deletion should succeed", deleteResult.success)
        assertFalse("Deleted file should not exist", newFile.exists())

        // 6. Verify security: path traversal prevention
        val traversalResult = retrievedService.readFile("../outside.txt")
        assertFalse("Path traversal should be prevented", traversalResult.success)
        assertTrue(
            "Error should mention path traversal",
            traversalResult.error?.contains("Path traversal") == true
        )
    }
}
