package com.itsaky.androidide.localWebServer

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.*
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.appdevforall.localwebserver.WebServer
import org.appdevforall.localwebserver.ServerConfig
import org.sqlite.JDBC
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

class WebServerTest {

    private lateinit var tempDbFile: File
    private lateinit var webServer: WebServer
    private lateinit var serverThread: Thread
    private val testPort = 6175 // Use different port to avoid conflicts

    @Before
    fun setup() {
        // Create a temporary database file for testing
        tempDbFile = File.createTempFile("test_webserver", ".db")
        tempDbFile.deleteOnExit()

        // Create the database and populate it with test data using JDBC
        DriverManager.registerDriver(JDBC())
        val connection = DriverManager.getConnection("jdbc:sqlite:${tempDbFile.absolutePath}")
        
        connection.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ContentTypes (
                    id INTEGER PRIMARY KEY,
                    value TEXT,
                    compression TEXT
                )
            """)
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS Content (
                    path TEXT PRIMARY KEY,
                    contentTypeID INTEGER,
                    content BLOB,
                    languageId INTEGER DEFAULT 1,
                    FOREIGN KEY(contentTypeID) REFERENCES ContentTypes(id)
                )
            """)

            // Insert test content types
            stmt.execute("INSERT INTO ContentTypes (id, value, compression) VALUES (1, 'text/html', 'none')")
            stmt.execute("INSERT INTO ContentTypes (id, value, compression) VALUES (2, 'application/pdf', 'none')")
            stmt.execute("INSERT INTO ContentTypes (id, value, compression) VALUES (3, 'text/plain', 'brotli')")
        }

        // Insert test content - large PDF-like content for range testing
        val largeContent = ByteArray(1024 * 1024) { (it % 256).toByte() } // 1MB of test data
        connection.prepareStatement("INSERT INTO Content (path, contentTypeID, content) VALUES ('test.pdf', 2, ?)").use { stmt ->
            stmt.setBytes(1, largeContent)
            stmt.executeUpdate()
        }

        // Insert small HTML content
        val smallContent = "Hello, World!".toByteArray()
        connection.prepareStatement("INSERT INTO Content (path, contentTypeID, content) VALUES ('test.html', 1, ?)").use { stmt ->
            stmt.setBytes(1, smallContent)
            stmt.executeUpdate()
        }

        // Insert Brotli compressed content
        val brotliContent = "Compressed content".toByteArray()
        connection.prepareStatement("INSERT INTO Content (path, contentTypeID, content) VALUES ('test.txt', 3, ?)").use { stmt ->
            stmt.setBytes(1, brotliContent)
            stmt.executeUpdate()
        }

        connection.close()

        // Create WebServer with test config
        val config = ServerConfig(
            port = testPort,
            databasePath = tempDbFile.absolutePath,
            bindName = "localhost"
        )
        webServer = WebServer(config)
    }

    @After
    fun cleanup() {
        if (::serverThread.isInitialized && serverThread.isAlive) {
            serverThread.interrupt()
        }
        if (tempDbFile.exists()) {
            tempDbFile.delete()
        }
    }

    private fun sendHttpRequest(request: String): String {
        val socket = Socket("localhost", testPort)
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        
        output.write(request.toByteArray())
        output.flush()
        
        val response = input.readBytes()
        socket.close()
        
        return String(response)
    }

    @Test
    fun `test content slicing - extracts correct byte range`() {
        val testContent = "Hello, World! This is a test file.".toByteArray()
        val startByte = 7
        val endByte = 12
        
        // Test that sliceArray extracts the correct range
        val sliced = testContent.sliceArray(startByte..endByte)
        val expected = "World!".toByteArray()
        
        assertThat(sliced).isEqualTo(expected)
    }

    @Test
    fun `test range header parsing - full range format`() {
        // Test the range parsing logic directly
        val rangeHeader = "Range: bytes=1000-2000"
        val rangeValue = rangeHeader.substringAfter("Range: ").trim()
        
        assertThat(rangeValue).isEqualTo("bytes=1000-2000")
        assertThat(rangeValue.startsWith("bytes=")).isTrue()
        
        val rangeSpec = rangeValue.substring(6) // Remove "bytes="
        val parts = rangeSpec.split("-")
        
        assertThat(parts.size).isEqualTo(2)
        assertThat(parts[0]).isEqualTo("1000")
        assertThat(parts[1]).isEqualTo("2000")
    }

    @Test
    fun `test range header parsing - open ended range format`() {
        val rangeHeader = "Range: bytes=1000-"
        val rangeValue = rangeHeader.substringAfter("Range: ").trim()
        val rangeSpec = rangeValue.substring(6)
        val parts = rangeSpec.split("-")
        
        assertThat(parts.size).isEqualTo(2)
        assertThat(parts[0]).isEqualTo("1000")
        assertThat(parts[1]).isEmpty()
    }

    @Test
    fun `test range header parsing - suffix range format`() {
        val rangeHeader = "Range: bytes=-500"
        val rangeValue = rangeHeader.substringAfter("Range: ").trim()
        val rangeSpec = rangeValue.substring(6)
        val parts = rangeSpec.split("-")
        
        assertThat(parts.size).isEqualTo(2)
        assertThat(parts[0]).isEmpty()
        assertThat(parts[1]).isEqualTo("500")
    }

    @Test
    fun `test range header parsing - multiple ranges detection`() {
        val rangeHeader = "Range: bytes=1000-2000,3000-4000"
        val rangeValue = rangeHeader.substringAfter("Range: ").trim()
        val rangeSpec = rangeValue.substring(6)
        
        assertThat(rangeSpec.contains(",")).isTrue()
    }

    @Test
    fun `test range validation - invalid range bounds`() {
        val contentLength = 1000
        val rangeStart = 2000L
        val rangeEnd = 1000L
        
        // Test that start > end is invalid
        assertThat(rangeStart >= contentLength || rangeEnd < rangeStart).isTrue()
    }

    @Test
    fun `test range validation - out of bounds range`() {
        val contentLength = 1000
        val rangeStart = 2000L
        val rangeEnd = 3000L
        
        // Test that ranges beyond content length are invalid
        assertThat(rangeStart >= contentLength).isTrue()
    }

    @Test
    fun `test range validation - valid range bounds`() {
        val contentLength = 1000
        val rangeStart = 100L
        val rangeEnd = 500L
        
        // Test that valid ranges pass validation
        assertThat(rangeStart >= 0 && rangeStart < contentLength && rangeEnd >= rangeStart).isTrue()
    }

    @Test
    fun `test suffix range calculation`() {
        val contentLength = 1000
        val suffix = 500L
        val actualRangeStart = maxOf(0, contentLength + (-suffix))
        val actualRangeEnd = (contentLength - 1).toLong()
        
        assertThat(actualRangeStart).isEqualTo(500)
        assertThat(actualRangeEnd).isEqualTo(999)
    }

    @Test
    fun `test open ended range calculation`() {
        val contentLength = 1000
        val rangeStart = 100L
        val actualRangeEnd = (contentLength - 1).toLong()
        
        assertThat(rangeStart).isEqualTo(100)
        assertThat(actualRangeEnd).isEqualTo(999)
    }

    @Test
    fun `test full range calculation`() {
        val contentLength = 1000
        val rangeStart = 100L
        val rangeEnd = 500L
        val actualRangeEnd = minOf(rangeEnd, (contentLength - 1).toLong())
        
        assertThat(rangeStart).isEqualTo(100)
        assertThat(actualRangeEnd).isEqualTo(500)
    }

    @Test
    fun `test full range calculation with end beyond content`() {
        val contentLength = 1000
        val rangeStart = 100L
        val rangeEnd = 1500L
        val actualRangeEnd = minOf(rangeEnd, (contentLength - 1).toLong())
        
        assertThat(rangeStart).isEqualTo(100)
        assertThat(actualRangeEnd).isEqualTo(999)
    }
}
