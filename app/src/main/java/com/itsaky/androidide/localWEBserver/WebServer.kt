package com.itsaky.androidide.localWEBserver

import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Files
import java.io.File
import java.io.ByteArrayInputStream
import org.brotli.dec.BrotliInputStream
import org.slf4j.LoggerFactory

import android.database.sqlite.SQLiteDatabase
import android.database.Cursor

/**
 * Configuration data class holding static default values.
 */
data class ServerConfig(
    val port: Int = 6174,
    val documentRoot: File = File("/data/data/com.itsaky.androidide/files/www-static"),
    // TODO: using documentation.db until modified to documentation.sqlite - jm 2025-06-16
    val sqliteDbPath: File = File("/data/data/com.itsaky.androidide/databases/documentation.db"),
)

/**
 * Main web server class.
 */
class WebServer(private val config: ServerConfig = ServerConfig()) {
    private var running = false
    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThread: Thread
    private val log = LoggerFactory.getLogger(WebServer::class.java)

    // TODO: disabled until document root is populated - jm 2025-06-16
    //if (!config.documentRoot.exists()) {
    //    println("Error: Document root directory does not exist: ${config.documentRoot}")
    //    return
    //}

    fun start() {
        if (running) {
            log.info("Server already running, ignoring start request")
            return
        }

        if (!config.sqliteDbPath.exists()) {
            log.error("SQLite database file does not exist: ${config.sqliteDbPath}")
            return
        }

        log.info("Starting web server on port ${config.port}...")
        log.info("Database path: ${config.sqliteDbPath}")
        log.info("Database exists: ${config.sqliteDbPath.exists()}")
        running = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(config.port, 0, InetAddress.getByName("0.0.0.0"))
                log.info("Server socket created successfully")
                log.info("Server started on port ${config.port}")
                log.info("Document root: ${config.documentRoot}")
                log.info("SQLite database: ${config.sqliteDbPath}")

                serverSocket.use {
                    while (running) {
                        try {
                            log.info("Waiting for client connection on port ${config.port}...")
                            val client: Socket = it.accept()
                            log.info("New client connected from: ${client.inetAddress.hostAddress}")
                            handleClient(client)
                        } catch (e: Exception) {
                            if (running) {
                                log.error("Error handling client connection", e)
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Fatal server error", e)
            } finally {
                stop()
            }
        }
        serverThread.start()
        log.info("Server thread started successfully")
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = PrintWriter(clientSocket.getOutputStream(), true)
            val output = clientSocket.getOutputStream()

            val requestLine = reader.readLine()
            log.info("Received request: $requestLine")

            if (requestLine == null) {
                log.warn("Empty request received")
                return
            }

            val parts = requestLine.split(" ")
            if (parts.size != 3) {
                log.warn("Invalid request format: $requestLine")
                sendError(writer, 400, "Bad Request")
                return
            }

            val method = parts[0]
            val path = parts[1]
            log.info("Processing $method request for path: $path")

            if (method != "GET") {
                log.warn("Unsupported method: $method")
                sendError(writer, 501, "Not Implemented")
                return
            }

            val filePath = config.documentRoot.resolve(path.substring(1)).normalize()
            log.info("Looking for static file at: $filePath")

            if (!filePath.startsWith(config.documentRoot)) {
                log.warn("Access attempt outside document root: $filePath")
                sendError(writer, 403, "Forbidden")
                return
            }

            // TODO: disabled until document root is populated - jm 2025-06-16
            //       take out false to enable
            if (filePath.exists() && filePath.isFile && false) {
                log.info("Found static file, serving from filesystem")
                val content = filePath.readBytes()
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
                log.info("Static file not found, checking database for path: $path")
                val dbPath = config.sqliteDbPath
                log.info("Using database at: $dbPath")

                val db: SQLiteDatabase = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
                log.info("Database connection opened")

                val selection = "c.path = ? OR c.path = ?"
                val selectionArgs = arrayOf(path, path.substring(1))

                val query = """
                                SELECT c.content, ct.value AS mime_type, ct.compression
                                FROM Content c
                                JOIN ContentTypes ct ON c.contentTypeID = ct.id
                                WHERE $selection
                                LIMIT 1
                            """

                val cursor = db.rawQuery(query, selectionArgs)

                if (cursor.moveToFirst()) {
                    log.info("Found content in database")
                    var dbContent = cursor.getBlob(cursor.getColumnIndexOrThrow("content"))
                    val dbMimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type"))
                    val compression = cursor.getString(cursor.getColumnIndexOrThrow("compression"))

                    if (compression == "brotli") {
                        try {
                            log.info("Decompressing Brotli content")
                            dbContent = BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                        } catch (e: Exception) {
                            log.error("Error decompressing Brotli content", e)
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
                    log.info("Content sent successfully")
                } else {
                    log.info("No content found in database for path: $path")
                    sendError(writer, 404, "Not Found")
                }

                cursor.close()
                db.close()
                log.info("Database connection closed")
            }
        } catch (e: Exception) {
            log.error("Error handling client request", e)
            sendError(PrintWriter(clientSocket.getOutputStream(), true), 500, "Internal Server Error")
        } finally {
            clientSocket.close()
            log.info("Client connection closed")
        }
    }

    private fun sendError(writer: PrintWriter, code: Int, message: String) {
        log.info("Sending error response: $code $message")
        writer.println("HTTP/1.1 $code $message")
        writer.println("Content-Type: text/plain")
        writer.println("Connection: close")
        writer.println()
        writer.println("$code $message")
    }

    fun stop() {
        log.info("Stopping server...")
        running = false
        if (::serverSocket.isInitialized) {
            serverSocket.close()
            log.info("Server socket closed")
        }
        if (::serverThread.isInitialized) {
            serverThread.interrupt()
            log.info("Server thread interrupted")
        }
        log.info("Server stopped successfully")
    }
}
