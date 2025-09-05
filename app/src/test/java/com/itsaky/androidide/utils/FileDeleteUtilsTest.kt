
package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files


class FileDeleteUtilsTest {

    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        // Clean up any remaining temp files/directories
        tempFiles.forEach { file ->
            if (file.exists()) {
                file.deleteRecursively()
            }
        }
        tempFiles.clear()
    }

    @Test
    fun `delete file and confirm it no longer exists`() {
        val tempFile = Files.createTempFile("test-file", ".txt").toFile()
        tempFile.writeText("test content")
        tempFiles.add(tempFile)
        
        FileDeleteUtils.deleteRecursive(tempFile)
        
        assertThat(tempFile.exists()).isFalse()
    }

    @Test
    fun `delete directory and confirm it no longer exists`() {
        val tempDir = Files.createTempDirectory("test-project").toFile()
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("test content")
        tempFiles.add(tempDir)
        
        FileDeleteUtils.deleteRecursive(tempDir)
        
        assertThat(tempDir.exists()).isFalse()
    }
}