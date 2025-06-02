package org.appdevforall.localwebserver

import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Files
import com.moandjiezana.toml.Toml
import java.io.File
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import java.io.ByteArrayInputStream

/**
 * Configuration data class to hold server settings
 */
data class ServerConfig(
    val port: Int = 8080,
    val documentRoot: Path,
    val sqliteDbPath: Path
)

/**
 * Main entry point for the web server application.
 * This is a simple implementation that will be expanded based on the requirements.
 */
class WebServer(private val config: ServerConfig) {
    private var running = false
    private lateinit var serverSocket: ServerSocket

    companion object {
        /**
         * Loads the server configuration from a TOML file
         */
        fun loadConfig(configPath: String = "config.toml"): ServerConfig {
            val toml = Toml().read(File(configPath))
            
            return ServerConfig(
                port = toml.getLong("port")?.toInt() ?: 8080,
                documentRoot = Path.of(toml.getString("document_root") ?: throw IllegalArgumentException("document_root is required")),
                sqliteDbPath = Path.of(toml.getString("sqlite_db_path") ?: throw IllegalArgumentException("sqlite_db_path is required"))
            )
        }
    }

    fun start() {
        try {
            // Ensure Brotli4j native library is loaded
            Brotli4jLoader.ensureAvailability()

            // Check if document root exists before starting the server
            if (!Files.exists(config.documentRoot)) {
                println("Error: Document root directory does not exist: ${config.documentRoot}")
                System.exit(1)
            }

            // Check if SQLite database exists before starting the server
            if (!Files.exists(config.sqliteDbPath)) {
                println("Error: SQLite database file does not exist: ${config.sqliteDbPath}")
                System.exit(1)
            }

            serverSocket = ServerSocket(config.port)
            running = true
            println("Server started on port ${config.port}")
            println("Document root: ${config.documentRoot}")
            println("SQLite database: ${config.sqliteDbPath}")
            
            while (running) {
                val clientSocket = serverSocket.accept()
                handleClient(clientSocket)
            }
        } catch (e: Exception) {
            println("Error: ${e.message}")
        } finally {
            stop()
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = PrintWriter(clientSocket.getOutputStream(), true)
            val output = clientSocket.getOutputStream()

            // Read the request line
            val requestLine = reader.readLine()
            println("Received request: $requestLine")
            
            if (requestLine == null) {
                return
            }

            // Parse the request
            val parts = requestLine.split(" ")
            if (parts.size != 3) {
                sendError(writer, 400, "Bad Request")
                return
            }

            val method = parts[0]
            val path = parts[1]
            
            // Only support GET method for now
            if (method != "GET") {
                sendError(writer, 501, "Not Implemented")
                return
            }

            // Serve static file
            val filePath = config.documentRoot.resolve(path.substring(1)).normalize()
            println("Looking for static file at: $filePath")
            
            // Security check: ensure the file is within the document root
            if (!filePath.startsWith(config.documentRoot)) {
                println("File path outside document root: $filePath")
                sendError(writer, 403, "Forbidden")
                return
            }

            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                println("Found static file, serving from filesystem")
                val content = Files.readAllBytes(filePath)
                val contentType = when {
                    filePath.toString().endsWith(".png") -> "image/png"
                    filePath.toString().endsWith(".jpg") || filePath.toString().endsWith(".jpeg") -> "image/jpeg"
                    filePath.toString().endsWith(".gif") -> "image/gif"
                    filePath.toString().endsWith(".html") -> "text/html; charset=UTF-8"
                    filePath.toString().endsWith(".css") -> "text/css; charset=UTF-8"
                    filePath.toString().endsWith(".js") -> "application/javascript; charset=UTF-8"
                    filePath.toString().endsWith(".json") -> "application/json; charset=UTF-8"
                    filePath.toString().endsWith(".md") -> "text/markdown; charset=UTF-8"
                    filePath.toString().endsWith(".pdf") -> "application/pdf"
                    filePath.toString().endsWith(".ttf") -> "font/ttf"
                    filePath.toString().endsWith(".xml") -> "application/xml; charset=UTF-8"
                    filePath.toString().endsWith(".txt") -> "text/plain; charset=UTF-8"
                    else -> "application/octet-stream"
                }
                writer.println("HTTP/1.1 200 OK")
                writer.println("Content-Type: $contentType")
                writer.println("Content-Length: ${content.size}")
                writer.println("Connection: close")
                writer.println()
                writer.flush()
                output.write(content)
                output.flush()
            } else {
                println("Static file not found, checking database for path: $path")
                // Static file not found, try to retrieve from SQLite database
                val dbPath = config.sqliteDbPath
                println("Using database at: $dbPath")
                val conn = org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:$dbPath" }.connection
                val query = """
                    SELECT c.content, ct.value AS mime_type, ct.compression
                    FROM Content c
                    JOIN ContentTypes ct ON c.contentTypeID = ct.id
                    WHERE c.path = ? OR c.path = ?
                    LIMIT 1
                """
                val pstmt = conn.prepareStatement(query)
                // Try both with and without leading slash
                pstmt.setString(1, path)
                pstmt.setString(2, path.substring(1))  // Remove leading slash
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    println("Found content in database")
                    var dbContent = rs.getBytes("content")
                    val dbMimeType = rs.getString("mime_type")
                    val compression = rs.getString("compression")
                    println("Content type: $dbMimeType, compression: $compression")
                    
                    // If content is Brotli compressed, decompress it
                    if (compression == "brotli") {
                        try {
                            println("Decompressing Brotli content")
                            val decompressed = BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                            dbContent = decompressed
                            println("Decompressed size: ${dbContent.size} bytes")
                        } catch (e: Exception) {
                            println("Error decompressing Brotli content: ${e.message}")
                            sendError(writer, 500, "Internal Server Error")
                            return
                        }
                    }
                    
                    writer.println("HTTP/1.1 200 OK")
                    writer.println("Content-Type: $dbMimeType")
                    writer.println("Content-Length: ${dbContent.size}")
                    writer.println("Connection: close")
                    writer.println()
                    writer.flush()
                    output.write(dbContent)
                    output.flush()
                    println("Content sent successfully")
                } else {
                    println("No content found in database for path: $path")
                    sendError(writer, 404, "Not Found")
                }
                rs.close()
                pstmt.close()
                conn.close()
            }

        } catch (e: Exception) {
            println("Error handling client: ${e.message}")
            e.printStackTrace()
            try {
                val writer = PrintWriter(clientSocket.getOutputStream(), true)
                sendError(writer, 500, "Internal Server Error")
            } catch (e: Exception) {
                println("Error sending error response: ${e.message}")
            }
        } finally {
            clientSocket.close()
        }
    }

    private fun sendError(writer: PrintWriter, code: Int, message: String) {
        writer.println("HTTP/1.1 $code $message")
        writer.println("Content-Type: text/plain")
        writer.println("Connection: close")
        writer.println()
        writer.println("$code $message")
    }

    fun stop() {
        running = false
        if (::serverSocket.isInitialized) {
            serverSocket.close()
        }
        println("Server stopped")
    }
} 