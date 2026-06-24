package com.itsaky.androidide.plugins.manager

import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeResourceService
import com.itsaky.androidide.plugins.manager.context.ServiceRegistryImpl
import com.itsaky.androidide.plugins.manager.services.FileServiceImpl
import com.itsaky.androidide.plugins.manager.services.ProjectServiceImpl
import com.itsaky.androidide.plugins.manager.services.ResourceServiceImpl
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import java.io.File

class E2EIntegrationTest {

    private lateinit var tempProjectRoot: File

    @Before
    fun setUp() {
        tempProjectRoot = File.createTempFile("e2e-test-", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
    }

    @Test
    fun testCompletePluginWorkflow() {
        // Simulate realistic plugin scenario:
        // 1. Create a source file
        // 2. Add a dependency to build.gradle.kts
        // 3. Read resource strings

        val serviceRegistry = ServiceRegistryImpl()
        val fileService = FileServiceImpl(tempProjectRoot)
        val projectService = ProjectServiceImpl(tempProjectRoot)
        val resourceService = ResourceServiceImpl(tempProjectRoot)

        serviceRegistry.register(IdeFileService::class.java, fileService)
        serviceRegistry.register(IdeProjectService::class.java, projectService)
        serviceRegistry.register(IdeResourceService::class.java, resourceService)

        // Step 1: Create source file via FileService
        val createResult = fileService.createFile(
            "app/src/main/kotlin/com/example/Main.kt",
            "package com.example\n\nfun main() {\n    println(\"Hello\")\n}"
        )
        assertTrue("Failed to create source file: ${createResult.error}", createResult.success)

        // Step 2: Add dependency via ProjectService
        val buildFile = File(tempProjectRoot, "app/build.gradle.kts")
        buildFile.parentFile?.mkdirs()
        buildFile.writeText("""
            dependencies {
                implementation("androidx.core:core-ktx:1.9.0")
            }
        """.trimIndent())

        val depResult = projectService.addDependency(
            "app/build.gradle.kts",
            "implementation(\"com.google.code.gson:gson:2.10.1\")"
        )
        assertTrue("Failed to add dependency: ${depResult.error}", depResult.success)

        val updatedBuildContent = buildFile.readText()
        assertTrue("Dependency not added to build file", updatedBuildContent.contains("gson:2.10.1"))

        // Step 3: Access resource via ResourceService
        val stringResult = resourceService.getString("app_name")
        assertTrue("Failed to get string resource: ${stringResult.error}", stringResult.success)
        assertNotNull("String resource data is null", stringResult.data)

        // Step 4: Verify file service can read created file
        val readResult = fileService.readFile("app/src/main/kotlin/com/example/Main.kt")
        assertTrue("Failed to read created file: ${readResult.error}", readResult.success)
        assertTrue("File content mismatch", readResult.data!!.contains("package com.example"))

        // Step 5: Cleanup via FileService
        val deleteResult = fileService.deleteFile("app/src/main/kotlin/com/example/Main.kt")
        assertTrue("Failed to delete file: ${deleteResult.error}", deleteResult.success)
    }
}
