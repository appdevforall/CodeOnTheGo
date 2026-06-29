package com.itsaky.androidide.agent.utils

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class ChatTranscriptUtilsTest {

    private lateinit var cacheDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("transcript-test").toFile()
        context = mockk<Context> {
            every { this@mockk.cacheDir } returns cacheDir
        }
    }

    // --- writeTranscriptToCache ---

    @Test
    fun `writeTranscriptToCache creates file in chat_exports subdirectory`() {
        val file = ChatTranscriptUtils.writeTranscriptToCache(context, "hello")
        assertTrue(file.exists())
        assertEquals("chat_exports", file.parentFile!!.name)
    }

    @Test
    fun `writeTranscriptToCache writes content verbatim`() {
        val content = "User: hi\nAgent: hello!"
        val file = ChatTranscriptUtils.writeTranscriptToCache(context, content)
        assertEquals(content, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `writeTranscriptToCache filename starts with chat-transcript`() {
        val file = ChatTranscriptUtils.writeTranscriptToCache(context, "test")
        assertTrue(file.name.startsWith("chat-transcript-"))
    }

    @Test
    fun `writeTranscriptToCache filename ends with txt`() {
        val file = ChatTranscriptUtils.writeTranscriptToCache(context, "test")
        assertTrue(file.name.endsWith(".txt"))
    }

    @Test
    fun `writeTranscriptToCache two calls produce distinct filenames`() {
        val f1 = ChatTranscriptUtils.writeTranscriptToCache(context, "a")
        Thread.sleep(5)
        val f2 = ChatTranscriptUtils.writeTranscriptToCache(context, "b")
        assertTrue("Expected distinct filenames but got: ${f1.name} and ${f2.name}", f1.name != f2.name)
    }

    @Test
    fun `writeTranscriptToCache handles empty transcript`() {
        val file = ChatTranscriptUtils.writeTranscriptToCache(context, "")
        assertTrue(file.exists())
        assertEquals("", file.readText())
    }

    @Test
    fun `writeTranscriptToCache handles multiline unicode content`() {
        val content = "日本語\nكلام عربي\n中文"
        val file = ChatTranscriptUtils.writeTranscriptToCache(context, content)
        assertEquals(content, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `writeTranscriptToCache throws when exports path is a file not a directory`() {
        val exportFile = File(cacheDir, "chat_exports")
        exportFile.createNewFile()

        try {
            ChatTranscriptUtils.writeTranscriptToCache(context, "data")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(
                "Message should mention 'not a directory', was: ${e.message}",
                e.message?.contains("not a directory") == true
            )
        }
    }

    @Test
    fun `writeTranscriptToCache re-uses existing exports directory`() {
        val exportsDir = File(cacheDir, "chat_exports")
        exportsDir.mkdirs()

        val file = ChatTranscriptUtils.writeTranscriptToCache(context, "second write")
        assertEquals(exportsDir.canonicalPath, file.parentFile!!.canonicalPath)
    }
}
