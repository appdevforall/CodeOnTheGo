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
//import org.sqlite.SQLiteDataSource

import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import android.util.Log

import java.sql.DriverManager
import java.sql.Connection

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


        // TODO: disabled until document root is populated - jm 2025-06-16
        //if (!config.documentRoot.exists()) {
        //    println("Error: Document root directory does not exist: ${config.documentRoot}")
        //    return
        //}


    fun start() {
        if (running) {
            Log.i("WebServer", "Server already running, ignoring start request")
            return
        }

        if (!config.sqliteDbPath.exists()) {
            Log.e("WebServer", "SQLite database file does not exist: ${config.sqliteDbPath}")
            return
        }

        Log.i("WebServer", "Starting web server...")
        running = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(config.port, 0, InetAddress.getLoopbackAddress())
                Log.i("WebServer", "Server socket created successfully")
                Log.i("WebServer", "Server started on port ${config.port}")
                Log.i("WebServer", "Document root: ${config.documentRoot}")
                Log.i("WebServer", "SQLite database: ${config.sqliteDbPath}")

                serverSocket.use {
                    while (running) {
                        try {
                            Log.i("WebServer", "Waiting for client connection...")
                            val client: Socket = it.accept()
                            Log.i("WebServer", "New client connected from: ${client.inetAddress.hostAddress}")
                            handleClient(client)
                        } catch (e: Exception) {
                            if (running) {
                                Log.e("WebServer", "Error handling client connection", e)
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WebServer", "Fatal server error", e)
            } finally {
                stop()
            }
        }
        serverThread.start()
        Log.i("WebServer", "Server thread started successfully")
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = PrintWriter(clientSocket.getOutputStream(), true)
            val output = clientSocket.getOutputStream()

            val requestLine = reader.readLine()
            Log.i("WebServer", "Received request: $requestLine")

            if (requestLine == null) {
                Log.w("WebServer", "Empty request received")
                return
            }

            val parts = requestLine.split(" ")
            if (parts.size != 3) {
                Log.w("WebServer", "Invalid request format: $requestLine")
                sendError(writer, 400, "Bad Request")
                return
            }

            val method = parts[0]
            val path = parts[1]
            Log.i("WebServer", "Processing $method request for path: $path")

            if (method != "GET") {
                Log.w("WebServer", "Unsupported method: $method")
                sendError(writer, 501, "Not Implemented")
                return
            }

            val filePath = config.documentRoot.resolve(path.substring(1)).normalize()
            Log.i("WebServer", "Looking for static file at: $filePath")

            if (!filePath.startsWith(config.documentRoot)) {
                Log.w("WebServer", "Access attempt outside document root: $filePath")
                sendError(writer, 403, "Forbidden")
                return
            }

            // TODO: disabled until document root is populated - jm 2025-06-16
            //       take out false to enable
            if (filePath.exists() && filePath.isFile && false) {
                println("Found static file, serving from filesystem")
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
                Log.i("WebServer", "Static file not found, checking database for path: $path")
                val dbPath = config.sqliteDbPath
                Log.i("WebServer", "Using database at: $dbPath")

                val db: SQLiteDatabase = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
                Log.i("WebServer", "Database connection opened")

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
                    Log.i("WebServer", "Found content in database")
                    var dbContent = cursor.getBlob(cursor.getColumnIndexOrThrow("content"))
                    val dbMimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type"))
                    val compression = cursor.getString(cursor.getColumnIndexOrThrow("compression"))

                    if (compression == "brotli") {
                        try {
                            Log.i("WebServer", "Decompressing Brotli content")
                            dbContent = BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                        } catch (e: Exception) {
                            Log.e("WebServer", "Error decompressing Brotli content", e)
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
                    Log.i("WebServer", "Content sent successfully")
                } else {
                    Log.i("WebServer", "No content found in database for path: $path")
                    sendError(writer, 404, "Not Found")
                }

                cursor.close()
                db.close()
                Log.i("WebServer", "Database connection closed")


//                val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
//
//                val query = """
//                    SELECT c.content, ct.value AS mime_type, ct.compression
//                    FROM Content c
//                    JOIN ContentTypes ct ON c.contentTypeID = ct.id
//                    WHERE c.path = ? OR c.path = ?
//                    LIMIT 1
//                """
//                val pstmt = conn.prepareStatement(query)
//                pstmt.setString(1, path)
//                pstmt.setString(2, path.substring(1))
//                val rs = pstmt.executeQuery()
//                if (rs.next()) {
//                    println("Found content in database")
//                    var dbContent = rs.getBytes("content")
//                    val dbMimeType = rs.getString("mime_type")
//                    val compression = rs.getString("compression")
//
//                    if (compression == "brotli") {
//                        try {
//                            println("Decompressing Brotli content")
//                            dbContent = BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
//                        } catch (e: Exception) {
//                            println("Error decompressing Brotli content: ${e.message}")
//                            sendError(writer, 500, "Internal Server Error")
//                            return
//                        }
//                    }
//
//                    writer.println("HTTP/1.1 200 OK")
//                    writer.println("Content-Type: $dbMimeType")
//                    writer.println("Content-Length: ${dbContent.size}")
//                    writer.println("Connection: close")
//                    writer.println()
//                    writer.flush()
//                    output.write(dbContent)
//                    output.flush()
//                    println("Content sent successfully")
//                } else {
//                    println("No content found in database for path: $path")
//                    sendError(writer, 404, "Not Found")
//                }
//                rs.close()
//                pstmt.close()
//                conn.close()
            }
        } catch (e: Exception) {
            Log.e("WebServer", "Error handling client request", e)
            sendError(PrintWriter(clientSocket.getOutputStream(), true), 500, "Internal Server Error")
        } finally {
            clientSocket.close()
            Log.i("WebServer", "Client connection closed")
        }
    }

    private fun sendError(writer: PrintWriter, code: Int, message: String) {
        Log.i("WebServer", "Sending error response: $code $message")
        writer.println("HTTP/1.1 $code $message")
        writer.println("Content-Type: text/plain")
        writer.println("Connection: close")
        writer.println()
        writer.println("$code $message")
    }

    fun stop() {
        Log.i("WebServer", "Stopping server...")
        running = false
        if (::serverSocket.isInitialized) {
            serverSocket.close()
            Log.i("WebServer", "Server socket closed")
        }
        if (::serverThread.isInitialized) {
            serverThread.interrupt()
            Log.i("WebServer", "Server thread interrupted")
        }
        Log.i("WebServer", "Server stopped successfully")
    }
}
