package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeProjectService
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ProjectServiceImplTest {

    @Test
    fun testAddDependencyValidatesBuildFile() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val service = ProjectServiceImpl(projectRoot)

        val result = service.addDependency("random.txt", "implementation 'com.example:lib:1.0'")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("build.gradle"))
    }

    @Test
    fun testAddDependencySuccess() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val buildFile = File(projectRoot, "app/build.gradle.kts")
        buildFile.parentFile!!.mkdirs()
        buildFile.writeText("""
            dependencies {
                implementation("androidx.core:core-ktx:1.9.0")
            }
        """.trimIndent())

        val service = ProjectServiceImpl(projectRoot)
        val result = service.addDependency(
            "app/build.gradle.kts",
            "implementation(\"com.google.code.gson:gson:2.10.1\")"
        )

        assertTrue(result.success)
        val content = buildFile.readText()
        assertTrue(content.contains("gson:2.10.1"))
    }

    @Test
    fun testTriggerGradleSync() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val service = ProjectServiceImpl(projectRoot)

        val result = service.triggerGradleSync()

        assertTrue(result.success)
        assertEquals("Gradle sync triggered", result.message)
    }

    @Test
    fun testIsBuildRunning() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val service = ProjectServiceImpl(projectRoot)

        val result = service.isBuildRunning()

        assertTrue(result.success)
        assertEquals("false", result.data)
    }

    @Test
    fun testRunApp() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val service = ProjectServiceImpl(projectRoot)

        val result = service.runApp()

        assertTrue(result.success)
        assertEquals("App run initiated", result.message)
    }

    @Test
    fun testGetBuildOutput() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val service = ProjectServiceImpl(projectRoot)

        val result = service.getBuildOutput()

        assertTrue(result.success)
        assertNotNull(result.data)
    }
}
