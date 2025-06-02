package org.appdevforall.localwebserver

import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.net.URL
import java.net.HttpURLConnection
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream

class WebServerTest {
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var server: WebServer
    private val testPort = 8082
    private val testContent = "Hello, Test World!"
    
    @BeforeEach
    fun setup() {
        // Create test directories
        val staticDir = tempDir.resolve("static")
        val dataDir = tempDir.resolve("data")
        Files.createDirectories(staticDir)
        Files.createDirectories(dataDir)
        
        // Create test file
        val testFile = staticDir.resolve("test.txt")
        Files.write(testFile, testContent.toByteArray())
        
        // Create empty SQLite database file
        val dbFile = dataDir.resolve("test.db")
        Files.createFile(dbFile)
        
        // Create test config
        val config = ServerConfig(
            port = testPort,
            documentRoot = staticDir,
            sqliteDbPath = dbFile
        )
        
        // Start server in a separate thread
        server = WebServer(config)
        Thread {
            server.start()
        }.start()
        
        // Give the server time to start
        Thread.sleep(1000)
    }
    
    private fun createStaticFile(filename: String, content: ByteArray) {
        val staticDir = tempDir.resolve("static")
        val file = staticDir.resolve(filename)
        Files.write(file, content)
    }
    
    private fun makeHttpRequest(filename: String): Triple<Int, String, ByteArray> {
        val url = URL("http://localhost:$testPort/$filename")
        var connection: HttpURLConnection? = null
        var retries = 3
        var lastException: Exception? = null

        while (retries > 0) {
            try {
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                val responseCode = connection.responseCode
                val contentType = connection.getHeaderField("Content-Type")
                val response = try {
                    connection.inputStream.readBytes()
                } catch (e: Exception) {
                    // For error responses, use errorStream
                    connection.errorStream?.readBytes() ?: ByteArray(0)
                }
                return Triple(responseCode, contentType, response)
            } catch (e: Exception) {
                lastException = e
                retries--
                if (retries > 0) {
                    Thread.sleep(1000) // Wait for 1 second before retrying
                }
            } finally {
                connection?.disconnect()
            }
        }

        throw lastException ?: RuntimeException("Failed to connect to the server after multiple retries.")
    }
    
    @ParameterizedTest
    @MethodSource("provideFileTypes")
    fun `test static file serving`(filename: String, content: ByteArray, expectedContentType: String) {
        createStaticFile(filename, content)
        val (responseCode, contentType, response) = makeHttpRequest(filename)
        assertEquals(200, responseCode)
        assertEquals(expectedContentType, contentType)
        assertTrue(response.contentEquals(content))
    }
    
    @ParameterizedTest
    @MethodSource("provideFileTypes")
    fun `test content type headers for all file types`(filename: String, content: ByteArray, expectedContentType: String) {
        createStaticFile(filename, content)
        val (responseCode, contentType, _) = makeHttpRequest(filename)
        assertEquals(200, responseCode, "Expected HTTP 200 OK for $filename")
        assertEquals(expectedContentType, contentType, "Expected Content-Type $expectedContentType for $filename")
    }
    
    @ParameterizedTest
    @MethodSource("provideFileTypes")
    fun `test content length headers for all file types`(filename: String, content: ByteArray, expectedContentType: String) {
        createStaticFile(filename, content)
        val url = URL("http://localhost:$testPort/$filename")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        
        val responseCode = connection.responseCode
        val contentLength = connection.getHeaderField("Content-Length")
        val responseBytes = connection.inputStream.readBytes()
        
        assertEquals(200, responseCode, "Expected HTTP 200 OK for $filename")
        assertEquals(content.size.toString(), contentLength, "Content-Length should match file size for $filename")
        assertTrue(responseBytes.contentEquals(content), "Response content should match original content for $filename")
    }
    
    companion object {
        @JvmStatic
        fun provideFileTypes(): List<Arguments> {
            return listOf(
                Arguments.of("test.txt", "Hello, Test World!".toByteArray(), "text/plain; charset=UTF-8"),
                Arguments.of("pixel.png", byteArrayOf(
                    0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
                    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
                    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
                    0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
                    0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
                    0x54.toByte(), 0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
                    0x05.toByte(), 0x00.toByte(), 0x01.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x2D.toByte(), 0xB4.toByte(), 0x00.toByte(),
                    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(),
                    0x42.toByte(), 0x60.toByte(), 0x82.toByte()
                ), "image/png"),
                Arguments.of("index.html", "<html><body>Hello HTML</body></html>".toByteArray(), "text/html; charset=UTF-8"),
                Arguments.of("style.css", "body { color: red; }".toByteArray(), "text/css; charset=UTF-8"),
                Arguments.of("app.js", "console.log('Hello JS');".toByteArray(), "application/javascript; charset=UTF-8"),
                Arguments.of("README.md", "# Hello Markdown".toByteArray(), "text/markdown; charset=UTF-8"),
                Arguments.of("data.json", """{"key":"value"}""".toByteArray(), "application/json; charset=UTF-8"),
                Arguments.of("photo.jpg", byteArrayOf(
                    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
                    0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
                    0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte()
                ), "image/jpeg"),
                Arguments.of("photo.jpeg", byteArrayOf(
                    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
                    0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
                    0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte()
                ), "image/jpeg"),
                Arguments.of("anim.gif", byteArrayOf(
                    0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x39.toByte(), 0x61.toByte(), 0x01.toByte(), 0x00.toByte(),
                    0x01.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21.toByte(), 0xF9.toByte(), 0x04.toByte(), 0x01.toByte(), 0x00.toByte(),
                    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x2C.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                    0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x02.toByte(), 0x44.toByte(),
                    0x01.toByte(), 0x00.toByte(), 0x3B.toByte()
                ), "image/gif"),
                Arguments.of("test.pdf", byteArrayOf(
                    0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte(), 0x2D.toByte(), 0x31.toByte(), 0x2E.toByte(), 0x34.toByte(), 0x0A.toByte(), // %PDF-1.4
                    0x25.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A.toByte(), // Binary comment
                    0x31.toByte(), 0x20.toByte(), 0x30.toByte(), 0x20.toByte(), 0x6F.toByte(), 0x62.toByte(), 0x6A.toByte(), 0x0A.toByte(), // 1 0 obj
                    0x3C.toByte(), 0x3C.toByte(), 0x2F.toByte(), 0x54.toByte(), 0x79.toByte(), 0x70.toByte(), 0x65.toByte(), 0x20.toByte(), 0x2F.toByte(), 0x43.toByte(), 0x61.toByte(), 0x74.toByte(), 0x61.toByte(), 0x6C.toByte(), 0x6F.toByte(), 0x67.toByte(), 0x3E.toByte(), 0x3E.toByte(), 0x0A.toByte(), // << /Type /Catalog>>
                    0x65.toByte(), 0x6E.toByte(), 0x64.toByte(), 0x6F.toByte(), 0x62.toByte(), 0x6A.toByte(), 0x0A.toByte(), // endobj
                    0x78.toByte(), 0x72.toByte(), 0x65.toByte(), 0x66.toByte(), 0x0A.toByte(), // xref
                    0x30.toByte(), 0x20.toByte(), 0x31.toByte(), 0x0A.toByte(), // 0 1
                    0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x20.toByte(), 0x36.toByte(), 0x35.toByte(), 0x35.toByte(), 0x33.toByte(), 0x35.toByte(), 0x20.toByte(), 0x66.toByte(), 0x0A.toByte(), // 0000000000 65535 f
                    0x74.toByte(), 0x72.toByte(), 0x61.toByte(), 0x69.toByte(), 0x6C.toByte(), 0x65.toByte(), 0x72.toByte(), 0x0A.toByte(), // trailer
                    0x3C.toByte(), 0x3C.toByte(), 0x2F.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte(), 0x65.toByte(), 0x20.toByte(), 0x31.toByte(), 0x3E.toByte(), 0x3E.toByte(), 0x0A.toByte(), // << /Size 1>>
                    0x73.toByte(), 0x74.toByte(), 0x61.toByte(), 0x72.toByte(), 0x74.toByte(), 0x78.toByte(), 0x72.toByte(), 0x65.toByte(), 0x66.toByte(), 0x0A.toByte(), // startxref
                    0x30.toByte(), 0x0A.toByte(), // 0
                    0x25.toByte(), 0x25.toByte(), 0x45.toByte(), 0x4F.toByte(), 0x46.toByte() // %%EOF
                ), "application/pdf"),
                Arguments.of("font.ttf", byteArrayOf(
                    0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x50.toByte()
                ), "font/ttf"),
                Arguments.of("data.xml", """<?xml version=\"1.0\"?><root><child>data</child></root>""".toByteArray(), "application/xml; charset=UTF-8")
            )
        }
    }
    
    @Test
    fun `test static PNG file serving`() {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
            0x54.toByte(), 0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x00.toByte(), 0x01.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x2D.toByte(), 0xB4.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(),
            0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
        createStaticFile("pixel.png", pngBytes)
        // Make HTTP request
        val url = URL("http://localhost:$testPort/pixel.png")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        // Read response
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val responseBytes = connection.inputStream.readBytes()

        // Verify response
        assertEquals(200, responseCode, "Expected HTTP 200 OK")
        assertEquals("image/png", contentType, "Content-Type should be image/png")
        assertTrue(responseBytes.contentEquals(pngBytes), "PNG file bytes should match exactly")
    }
    
    @Test
    fun `test static HTML file serving`() {
        val htmlContent = "<html><body>Hello HTML</body></html>"
        createStaticFile("index.html", htmlContent.toByteArray())
        val url = URL("http://localhost:$testPort/index.html")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("text/html; charset=UTF-8", contentType)
        assertTrue(response.contentEquals(htmlContent.toByteArray()))
    }

    @Test
    fun `test static CSS file serving`() {
        val cssContent = "body { color: red; }"
        createStaticFile("style.css", cssContent.toByteArray())
        val url = URL("http://localhost:$testPort/style.css")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("text/css; charset=UTF-8", contentType)
        assertTrue(response.contentEquals(cssContent.toByteArray()))
    }

    @Test
    fun `test static JS file serving`() {
        val jsContent = "console.log('Hello JS');"
        createStaticFile("app.js", jsContent.toByteArray())
        val url = URL("http://localhost:$testPort/app.js")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("application/javascript; charset=UTF-8", contentType)
        assertTrue(response.contentEquals(jsContent.toByteArray()))
    }

    @Test
    fun `test static Markdown file serving`() {
        val mdContent = "# Hello Markdown"
        createStaticFile("README.md", mdContent.toByteArray())
        val url = URL("http://localhost:$testPort/README.md")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("text/markdown; charset=UTF-8", contentType)
        assertTrue(response.contentEquals(mdContent.toByteArray()))
    }

    @Test
    fun `test static JSON file serving`() {
        val jsonContent = """{"key":"value"}"""
        createStaticFile("data.json", jsonContent.toByteArray())
        val url = URL("http://localhost:$testPort/data.json")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("application/json; charset=UTF-8", contentType)
        assertTrue(response.contentEquals(jsonContent.toByteArray()))
    }

    @Test
    fun `test static JPG file serving`() {
        val jpgBytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
            0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte()
        )
        createStaticFile("photo.jpg", jpgBytes)
        val url = URL("http://localhost:$testPort/photo.jpg")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("image/jpeg", contentType)
        assertTrue(response.contentEquals(jpgBytes))
    }

    @Test
    fun `test static JPEG file serving`() {
        val jpegBytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
            0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte()
        )
        createStaticFile("photo.jpeg", jpegBytes)
        val url = URL("http://localhost:$testPort/photo.jpeg")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("image/jpeg", contentType)
        assertTrue(response.contentEquals(jpegBytes))
    }

    @Test
    fun `test static GIF file serving`() {
        val gifBytes = byteArrayOf(
            0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x39.toByte(), 0x61.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21.toByte(), 0xF9.toByte(), 0x04.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x2C.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x02.toByte(), 0x44.toByte(),
            0x01.toByte(), 0x00.toByte(), 0x3B.toByte()
        )
        createStaticFile("anim.gif", gifBytes)
        val url = URL("http://localhost:$testPort/anim.gif")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("image/gif", contentType)
        assertTrue(response.contentEquals(gifBytes))
    }

    @Test
    fun `test static PDF file serving`() {
        // Create a minimal valid PDF file (just header and minimal structure)
        val pdfBytes = byteArrayOf(
            0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte(), 0x2D.toByte(), 0x31.toByte(), 0x2E.toByte(), 0x34.toByte(), 0x0A.toByte(), // %PDF-1.4
            0x25.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A.toByte(), // Binary comment
            0x31.toByte(), 0x20.toByte(), 0x30.toByte(), 0x20.toByte(), 0x6F.toByte(), 0x62.toByte(), 0x6A.toByte(), 0x0A.toByte(), // 1 0 obj
            0x3C.toByte(), 0x3C.toByte(), 0x2F.toByte(), 0x54.toByte(), 0x79.toByte(), 0x70.toByte(), 0x65.toByte(), 0x20.toByte(), 0x2F.toByte(), 0x43.toByte(), 0x61.toByte(), 0x74.toByte(), 0x61.toByte(), 0x6C.toByte(), 0x6F.toByte(), 0x67.toByte(), 0x3E.toByte(), 0x3E.toByte(), 0x0A.toByte(), // << /Type /Catalog>>
            0x65.toByte(), 0x6E.toByte(), 0x64.toByte(), 0x6F.toByte(), 0x62.toByte(), 0x6A.toByte(), 0x0A.toByte(), // endobj
            0x78.toByte(), 0x72.toByte(), 0x65.toByte(), 0x66.toByte(), 0x0A.toByte(), // xref
            0x30.toByte(), 0x20.toByte(), 0x31.toByte(), 0x0A.toByte(), // 0 1
            0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x20.toByte(), 0x36.toByte(), 0x35.toByte(), 0x35.toByte(), 0x33.toByte(), 0x35.toByte(), 0x20.toByte(), 0x66.toByte(), 0x0A.toByte(), // 0000000000 65535 f
            0x74.toByte(), 0x72.toByte(), 0x61.toByte(), 0x69.toByte(), 0x6C.toByte(), 0x65.toByte(), 0x72.toByte(), 0x0A.toByte(), // trailer
            0x3C.toByte(), 0x3C.toByte(), 0x2F.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte(), 0x65.toByte(), 0x20.toByte(), 0x31.toByte(), 0x3E.toByte(), 0x3E.toByte(), 0x0A.toByte(), // << /Size 1>>
            0x73.toByte(), 0x74.toByte(), 0x61.toByte(), 0x72.toByte(), 0x74.toByte(), 0x78.toByte(), 0x72.toByte(), 0x65.toByte(), 0x66.toByte(), 0x0A.toByte(), // startxref
            0x30.toByte(), 0x0A.toByte(), // 0
            0x25.toByte(), 0x25.toByte(), 0x45.toByte(), 0x4F.toByte(), 0x46.toByte() // %%EOF
        )
        createStaticFile("test.pdf", pdfBytes)
        val url = URL("http://localhost:$testPort/test.pdf")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("application/pdf", contentType)
        assertTrue(response.contentEquals(pdfBytes))
    }

    @Test
    fun `test static TTF file serving`() {
        // Minimal TTF header (not a valid font, but enough for testing)
        val ttfBytes = byteArrayOf(
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x50.toByte()
        )
        createStaticFile("font.ttf", ttfBytes)
        val url = URL("http://localhost:$testPort/font.ttf")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("font/ttf", contentType)
        assertTrue(response.contentEquals(ttfBytes))
    }

    @Test
    fun `test static XML file serving`() {
        val xmlContent = """<?xml version=\"1.0\"?><root><child>data</child></root>"""
        createStaticFile("data.xml", xmlContent.toByteArray())
        val url = URL("http://localhost:$testPort/data.xml")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("application/xml; charset=UTF-8", contentType)
        assertTrue(response.contentEquals(xmlContent.toByteArray()))
    }
    
    @Test
    fun `test static file precedence over database content`() {
        // Prepare SQLite DB schema and insert a txt file
        val dbPath = tempDir.resolve("data").resolve("test.db")
        val conn = org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:$dbPath" }.connection
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Content (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL,
                    languageID INTEGER NOT NULL,
                    content BLOB NOT NULL,
                    contentTypeID INTEGER NOT NULL
                );
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ContentTypes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    value TEXT NOT NULL UNIQUE,
                    compression TEXT NOT NULL
                );
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Languages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    value TEXT NOT NULL UNIQUE
                );
            """)
        }
        // Insert a language (required by schema)
        val langId = conn.prepareStatement("INSERT INTO Languages(value) VALUES (?)", java.sql.Statement.RETURN_GENERATED_KEYS).apply {
            setString(1, "en")
            executeUpdate()
        }.generatedKeys.let { rs -> rs.next(); rs.getInt(1) }
        // Insert a content type for text/plain
        val contentTypeId = conn.prepareStatement("INSERT INTO ContentTypes(value, compression) VALUES (?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS).apply {
            setString(1, "text/plain")
            setString(2, "none")
            executeUpdate()
        }.generatedKeys.let { rs -> rs.next(); rs.getInt(1) }
        // Insert the txt file content
        val testPath = "/dbfile.txt"
        val testContent = "Hello from DB!"
        conn.prepareStatement("INSERT INTO Content(path, languageID, content, contentTypeID) VALUES (?, ?, ?, ?)").apply {
            setString(1, testPath)
            setInt(2, langId)
            setBytes(3, testContent.toByteArray())
            setInt(4, contentTypeId)
            executeUpdate()
            close()
        }
        conn.close()
        // Create a static file with the same path but different content
        val staticDir = tempDir.resolve("static")
        val staticFile = staticDir.resolve("dbfile.txt")
        Files.write(staticFile, "Hello from static file!".toByteArray())
        // Make HTTP request (should hit DB, not static file)
        val url = URL("http://localhost:$testPort/dbfile.txt")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("text/plain; charset=UTF-8", contentType)
        assertTrue(response.contentEquals("Hello from static file!".toByteArray()))
    }
    
    @Test
    fun `test database content retrieval when static file does not exist`() {
        // Prepare SQLite DB schema and insert a txt file
        val dbPath = tempDir.resolve("data").resolve("test.db")
        val conn = org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:$dbPath" }.connection
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Content (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL,
                    languageID INTEGER NOT NULL,
                    content BLOB NOT NULL,
                    contentTypeID INTEGER NOT NULL
                );
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ContentTypes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    value TEXT NOT NULL UNIQUE,
                    compression TEXT NOT NULL
                );
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Languages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    value TEXT NOT NULL UNIQUE
                );
            """)
        }
        // Insert a language (required by schema)
        val langId = conn.prepareStatement("INSERT INTO Languages(value) VALUES (?)", java.sql.Statement.RETURN_GENERATED_KEYS).apply {
            setString(1, "en")
            executeUpdate()
        }.generatedKeys.let { rs -> rs.next(); rs.getInt(1) }
        // Insert a content type for text/plain
        val contentTypeId = conn.prepareStatement("INSERT INTO ContentTypes(value, compression) VALUES (?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS).apply {
            setString(1, "text/plain")
            setString(2, "none")
            executeUpdate()
        }.generatedKeys.let { rs -> rs.next(); rs.getInt(1) }
        // Insert the txt file content
        val testPath = "/dbonly.txt"
        val testContent = "Hello from DB!"
        conn.prepareStatement("INSERT INTO Content(path, languageID, content, contentTypeID) VALUES (?, ?, ?, ?)").apply {
            setString(1, testPath)
            setInt(2, langId)
            setBytes(3, testContent.toByteArray())
            setInt(4, contentTypeId)
            executeUpdate()
            close()
        }
        conn.close()
        // Make HTTP request (should hit DB, as no static file exists)
        val url = URL("http://localhost:$testPort/dbonly.txt")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val response = connection.inputStream.readBytes()
        assertEquals(200, responseCode)
        assertEquals("text/plain", contentType)
        assertTrue(response.contentEquals("Hello from DB!".toByteArray()))
    }
    
    @Test
    fun `test 404 error for file not found`() {
        // Ensure the database exists and has the correct schema
        val dbPath = tempDir.resolve("data").resolve("test.db")
        val conn = org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:$dbPath" }.connection
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Content (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL,
                    languageID INTEGER NOT NULL,
                    content BLOB NOT NULL,
                    contentTypeID INTEGER NOT NULL
                );
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ContentTypes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    value TEXT NOT NULL UNIQUE,
                    compression TEXT NOT NULL
                );
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Languages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    value TEXT NOT NULL UNIQUE
                );
            """)
        }
        conn.close()
        val (responseCode, contentType, response) = makeHttpRequest("nonexistent.txt")
        println("404 test debug: responseCode=$responseCode, contentType=$contentType, response='${String(response)}'")
        assertEquals(404, responseCode, "Expected HTTP 404 Not Found")
    }
    
    @Test
    fun `test UTF-8 content handling`() {
        // Test content with emojis, Hebrew, and Mandarin
        val content = """
            Hello World! 👋
            שלום עולם! (Shalom Olam!)
            你好世界！(Nǐ hǎo shìjiè!)
            🌍 🌎 🌏
            🎉 🎊 🎈
            אבגדהוזסעפצקרשת
            天地玄黄宇宙洪荒
        """.trimIndent()
        
        createStaticFile("unicode.txt", content.toByteArray(Charsets.UTF_8))
        val (responseCode, contentType, response) = makeHttpRequest("unicode.txt")
        
        assertEquals(200, responseCode, "Expected HTTP 200 OK")
        assertEquals("text/plain; charset=UTF-8", contentType, "Content-Type should include UTF-8 charset")
        assertEquals(content, String(response, Charsets.UTF_8), "Response content should match original UTF-8 content")
        
        // Verify Content-Length is correct for UTF-8 bytes
        val url = URL("http://localhost:$testPort/unicode.txt")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val contentLength = connection.getHeaderField("Content-Length")
        assertEquals(content.toByteArray(Charsets.UTF_8).size.toString(), contentLength, 
            "Content-Length should match UTF-8 byte size")
    }
    
    @Test
    fun `test Brotli compressed content from database`() {
        // Prepare SQLite DB schema and insert compressed content
        val dbPath = tempDir.resolve("data").resolve("test.db")
        val conn = org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:$dbPath" }.connection
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Content (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL,
                    languageID INTEGER NOT NULL,
                    content BLOB NOT NULL,
                    contentTypeID INTEGER NOT NULL
                );
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ContentTypes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    value TEXT NOT NULL UNIQUE,
                    compression TEXT NOT NULL
                );
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Languages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    value TEXT NOT NULL UNIQUE
                );
            """)
        }
        
        // Insert a language
        val langId = conn.prepareStatement("INSERT INTO Languages(value) VALUES (?)", java.sql.Statement.RETURN_GENERATED_KEYS).apply {
            setString(1, "en")
            executeUpdate()
        }.generatedKeys.let { rs -> rs.next(); rs.getInt(1) }
        
        // Insert a content type with Brotli compression
        val contentTypeId = conn.prepareStatement("INSERT INTO ContentTypes(value, compression) VALUES (?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS).apply {
            setString(1, "text/plain")
            setString(2, "brotli")
            executeUpdate()
        }.generatedKeys.let { rs -> rs.next(); rs.getInt(1) }
        
        // Create test content with more repetitive text to ensure good compression
        val originalContent = """
            Hello, this is a test of Brotli compression! 👋
            This is a repeated line to test compression.
            This is a repeated line to test compression.
            This is a repeated line to test compression.
            This is a repeated line to test compression.
            This is a repeated line to test compression.
            This is a repeated line to test compression.
            This is a repeated line to test compression.
            This is a repeated line to test compression.
            This is a repeated line to test compression.
        """.trimIndent()
        
        val originalBytes = originalContent.toByteArray(Charsets.UTF_8)
        println("Original content size: ${originalBytes.size} bytes")
        
        // Compress the content with Brotli using Brotli4j
        val baos = ByteArrayOutputStream()
        BrotliOutputStream(baos).use { brotli ->
            brotli.write(originalBytes)
        }
        val compressedContent = baos.toByteArray()
        println("Compressed content size: ${compressedContent.size} bytes")
        
        // Verify that compression actually reduced the size
        assertTrue(compressedContent.size < originalBytes.size, 
            "Compressed content should be smaller than original. Original: ${originalBytes.size}, Compressed: ${compressedContent.size}")
        
        // Insert the compressed content
        val testPath = "/compressed.txt"
        conn.prepareStatement("INSERT INTO Content(path, languageID, content, contentTypeID) VALUES (?, ?, ?, ?)").apply {
            setString(1, testPath)
            setInt(2, langId)
            setBytes(3, compressedContent)
            setInt(4, contentTypeId)
            executeUpdate()
            close()
        }
        conn.close()
        
        // Make HTTP request
        val (responseCode, contentType, response) = makeHttpRequest("compressed.txt")
        
        println("Expected bytes (length ${originalBytes.size}): ${originalBytes.joinToString(", ") { it.toString(16) }}")
        println("Actual bytes (length ${response.size}): ${response.joinToString(", ") { it.toString(16) }}")
        
        assertEquals(200, responseCode, "Expected HTTP 200 OK")
        assertEquals("text/plain", contentType, "Content-Type should be text/plain")
        assertTrue(response.contentEquals(originalBytes), "Decompressed content should match original content byte for byte")
    }
    
    @AfterEach
    fun cleanup() {
        server.stop()
    }
} 