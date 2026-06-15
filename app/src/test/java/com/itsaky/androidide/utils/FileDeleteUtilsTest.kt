
package com.itsaky.androidide.utils

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.viewmodel.MainDispatcherRule
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files


@OptIn(ExperimentalCoroutinesApi::class)
class FileDeleteUtilsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempFiles = mutableListOf<File>()

    @Before
    fun stubAndroidLog() {
        // FileDeleteUtils.deleteRecursive calls android.util.Log.d/Log.e on the IO
        // coroutine; under plain JVM unit tests those throw "not mocked" and the
        // coroutine never reaches the onDeleted callback.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun cleanup() {
        unmockkStatic(Log::class)
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

        val done = CompletableDeferred<Boolean>()
        FileDeleteUtils.deleteRecursive(tempFile) { done.complete(it) }
        runBlocking { done.await() }

        assertThat(tempFile.exists()).isFalse()
    }

    @Test
    fun `delete directory and confirm it no longer exists`() {
        val tempDir = Files.createTempDirectory("test-project").toFile()
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("test content")
        tempFiles.add(tempDir)

        val done = CompletableDeferred<Boolean>()
        FileDeleteUtils.deleteRecursive(tempDir) { done.complete(it) }
        runBlocking { done.await() }

        assertThat(tempDir.exists()).isFalse()
    }
}
