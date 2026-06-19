package com.itsaky.androidide.plugins.manager.core

import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeResourceService
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import java.io.File

class PluginManagerIntegrationTest {

    private lateinit var tempProjectRoot: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        // Create a temporary project root directory
        tempProjectRoot = File.createTempFile("test_project_", "").apply {
            delete()
            mkdirs()
        }

        // Create a test file with content
        testFile = File(tempProjectRoot, "test.txt")
        testFile.writeText("Integration test content")

        // Create a build.gradle file for project service tests
        val buildFile = File(tempProjectRoot, "build.gradle")
        buildFile.writeText("""
            plugins {
                id 'java'
            }

            dependencies {
            }
        """.trimIndent())
    }

    @After
    fun cleanup() {
        // Clean up temporary directory
        if (tempProjectRoot.exists()) {
            tempProjectRoot.deleteRecursively()
        }
    }

    @Test
    fun testFileServiceIntegration() {
        // Note: This test demonstrates the FileServiceImpl directly
        // In production, it would be obtained from PluginManager's service registry
        val fileService = com.itsaky.androidide.plugins.manager.services.FileServiceImpl(tempProjectRoot)

        // Test reading the file we created in setup
        val result = fileService.readFile("test.txt")
        assertTrue("File read should succeed", result.success)
        assertEquals("Integration test content", result.data)

        // Test creating a new file
        val createResult = fileService.createFile("new_file.txt", "New content")
        assertTrue("File creation should succeed", createResult.success)

        // Verify the file was actually created
        val newFile = File(tempProjectRoot, "new_file.txt")
        assertTrue("New file should exist", newFile.exists())
        assertEquals("New content", newFile.readText())

        // Test updating an existing file
        val updateResult = fileService.updateFile("test.txt", "Updated content")
        assertTrue("File update should succeed", updateResult.success)
        assertEquals("Updated content", testFile.readText())

        // Test listing files (use "." for root directory)
        val listResult = fileService.listFiles(".", false)
        assertTrue("File listing should succeed", listResult.success)
        assertNotNull("File list should not be null", listResult.data)
        assertTrue("File list should contain test.txt", listResult.data!!.contains("test.txt"))

        // Test deleting a file
        val deleteResult = fileService.deleteFile("new_file.txt")
        assertTrue("File deletion should succeed", deleteResult.success)
        assertFalse("Deleted file should not exist", newFile.exists())
    }

    @Test
    fun testProjectServiceIntegration() {
        // Note: This test demonstrates the ProjectServiceImpl directly
        // In production, it would be obtained from PluginManager's service registry
        val projectService = com.itsaky.androidide.plugins.manager.services.ProjectServiceImpl(tempProjectRoot)

        // Test adding a dependency
        val result = projectService.addDependency(
            "build.gradle",
            "implementation 'androidx.core:core-ktx:1.9.0'"
        )
        assertTrue("Dependency addition should succeed", result.success)

        // Verify the dependency was added to the build file
        val buildFile = File(tempProjectRoot, "build.gradle")
        val buildFileContent = buildFile.readText()
        assertTrue(
            "Build file should contain the new dependency",
            buildFileContent.contains("implementation 'androidx.core:core-ktx:1.9.0'")
        )

        // Test other stub methods (they should return success for now)
        val syncResult = projectService.triggerGradleSync()
        assertTrue("Gradle sync should succeed (stub)", syncResult.success)

        val buildStatusResult = projectService.isBuildRunning()
        assertTrue("Build status check should succeed (stub)", buildStatusResult.success)
        assertEquals("false", buildStatusResult.data)

        val runAppResult = projectService.runApp()
        assertTrue("App run should succeed (stub)", runAppResult.success)

        val buildOutputResult = projectService.getBuildOutput()
        assertTrue("Build output retrieval should succeed (stub)", buildOutputResult.success)
    }

    @Test
    fun testResourceServiceIntegration() {
        // Note: This test demonstrates the ResourceServiceImpl directly
        // In production, it would be obtained from PluginManager's service registry
        val resourceService = com.itsaky.androidide.plugins.manager.services.ResourceServiceImpl(tempProjectRoot)

        // Test stub methods (they should all return success with placeholder data)
        val stringResult = resourceService.getString("app_name")
        assertTrue("String resource retrieval should succeed (stub)", stringResult.success)
        assertEquals("Placeholder string", stringResult.data)

        val drawableResult = resourceService.getDrawable("ic_launcher")
        assertTrue("Drawable resource retrieval should succeed (stub)", drawableResult.success)
        assertEquals("Placeholder drawable path", drawableResult.data)

        val colorResult = resourceService.getColor("primary_color")
        assertTrue("Color resource retrieval should succeed (stub)", colorResult.success)
        assertEquals("#000000", colorResult.data)

        val addStringResult = resourceService.addStringResource("new_string", "New Value")
        assertTrue("String resource addition should succeed (stub)", addStringResult.success)
    }

    @Test
    fun testPathTraversalPrevention() {
        val fileService = com.itsaky.androidide.plugins.manager.services.FileServiceImpl(tempProjectRoot)

        // Test various path traversal attempts
        val traversalAttempts = listOf(
            "../outside.txt",
            "../../etc/passwd",
            "subdir/../../outside.txt",
            "./../outside.txt"
        )

        for (attempt in traversalAttempts) {
            val result = fileService.readFile(attempt)
            assertFalse(
                "Path traversal attempt should fail: $attempt",
                result.success
            )
            assertTrue(
                "Error message should indicate path traversal: $attempt",
                result.error?.contains("Path traversal") == true
            )
        }
    }

    @Test
    fun testProjectServiceBuildFileValidation() {
        val projectService = com.itsaky.androidide.plugins.manager.services.ProjectServiceImpl(tempProjectRoot)

        // Test invalid build file path
        val invalidResult = projectService.addDependency(
            "invalid.txt",
            "implementation 'test:test:1.0'"
        )
        assertFalse("Invalid build file should be rejected", invalidResult.success)
        assertTrue(
            "Error message should mention invalid build file",
            invalidResult.error?.contains("Invalid build file") == true
        )

        // Test non-existent build file
        val nonExistentResult = projectService.addDependency(
            "app/build.gradle",
            "implementation 'test:test:1.0'"
        )
        assertFalse("Non-existent build file should be rejected", nonExistentResult.success)
        assertTrue(
            "Error message should mention file doesn't exist",
            nonExistentResult.error?.contains("does not exist") == true
        )
    }

    @Test
    fun testFileServiceWithNestedDirectories() {
        val fileService = com.itsaky.androidide.plugins.manager.services.FileServiceImpl(tempProjectRoot)

        // Test creating a file in a nested directory
        val nestedPath = "src/main/kotlin/Test.kt"
        val createResult = fileService.createFile(nestedPath, "class Test {}")
        assertTrue("Nested file creation should succeed", createResult.success)

        // Verify the file was created
        val nestedFile = File(tempProjectRoot, nestedPath)
        assertTrue("Nested file should exist", nestedFile.exists())
        assertEquals("class Test {}", nestedFile.readText())

        // Test listing files recursively (use "." for root directory)
        val listResult = fileService.listFiles(".", true)
        assertTrue("Recursive listing should succeed", listResult.success)
        assertTrue(
            "Recursive listing should include nested file",
            listResult.data!!.contains(nestedPath)
        )
    }
}
