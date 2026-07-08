package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeFileService
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.io.File

class FileServiceImplTest {

    private lateinit var projectRoot: File

    @Before
    fun setUp() {
        // Create a unique temporary directory for each test
        projectRoot = File.createTempFile("test-project-", "").parentFile!!
        projectRoot.deleteRecursively()
        projectRoot.mkdirs()
    }

    @Test
    fun testReadFileRejectsPathTraversal() {
        val service = FileServiceImpl(projectRoot)
        val result = service.readFile("../../etc/passwd")

        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Path traversal"))
    }

    @Test
    fun testReadFileSuccess() {
        val testFile = File(projectRoot, "test.txt")
        testFile.writeText("Hello World")

        val service = FileServiceImpl(projectRoot)
        val result = service.readFile("test.txt")

        assertTrue(result.success)
        assertEquals("Hello World", result.data)
        assertNull(result.error)
    }

    @Test
    fun testCreateFileSuccess() {
        val service = FileServiceImpl(projectRoot)

        val result = service.createFile("new.txt", "Content")

        assertTrue("Result was not successful: ${result.error}", result.success)
        assertTrue("File does not exist at ${File(projectRoot, "new.txt").absolutePath}", File(projectRoot, "new.txt").exists())
    }

    @Test
    fun testUpdateFileSuccess() {
        val testFile = File(projectRoot, "test.txt")
        testFile.writeText("Original")

        val service = FileServiceImpl(projectRoot)
        val result = service.updateFile("test.txt", "Updated")

        assertTrue(result.success)
        assertEquals("Updated", testFile.readText())
    }

    @Test
    fun testDeleteFileSuccess() {
        val testFile = File(projectRoot, "test.txt")
        testFile.writeText("Delete me")

        val service = FileServiceImpl(projectRoot)
        val result = service.deleteFile("test.txt")

        assertTrue(result.success)
        assertFalse(testFile.exists())
    }

    @Test
    fun testListFilesNonRecursive() {
        File(projectRoot, "file1.txt").writeText("1")
        File(projectRoot, "file2.txt").writeText("2")
        val subdir = File(projectRoot, "subdir")
        subdir.mkdirs()
        File(subdir, "file3.txt").writeText("3")

        val service = FileServiceImpl(projectRoot)
        val result = service.listFiles(".", false)

        assertTrue(result.success)
        val files = result.data?.split("\n") ?: emptyList()
        assertTrue(files.contains("file1.txt"))
        assertTrue(files.contains("file2.txt"))
        assertFalse(files.contains("subdir/file3.txt"))
    }

    @Test
    fun testListFilesRecursive() {
        File(projectRoot, "file1.txt").writeText("1")
        val subdir = File(projectRoot, "subdir")
        subdir.mkdirs()
        File(subdir, "file2.txt").writeText("2")

        val service = FileServiceImpl(projectRoot)
        val result = service.listFiles(".", true)

        assertTrue(result.success)
        val files = result.data?.split("\n") ?: emptyList()
        assertTrue(files.contains("file1.txt"))
        assertTrue(files.any { it.contains("subdir") && it.contains("file2.txt") })
    }
}
