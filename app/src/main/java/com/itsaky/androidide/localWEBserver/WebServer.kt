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

    fun start() {
        if (running) return
        // TODO: disabled until document root is populated - jm 2025-06-16
        //if (!config.documentRoot.exists()) {
        //    println("Error: Document root directory does not exist: ${config.documentRoot}")
        //    return
        //}

        // Check if SQLite database exists before starting the server
        if (!config.sqliteDbPath.exists()) {
            println("Error: SQLite database file does not exist: ${config.sqliteDbPath}")
            return
        }

        running = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(config.port, 0, InetAddress.getLoopbackAddress())
                //serverSocket = ServerSocket(config.port)

                println("Server started on port ${config.port}")
                println("Document root: ${config.documentRoot}")
                println("SQLite database: ${config.sqliteDbPath}")

                serverSocket.use {
                    while (running) {
                        try {
                            val client: Socket = it.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            if (running) e.printStackTrace()
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                println("Error: ${e.message}")
            } finally {
                stop()
            }
        }
        serverThread.start()
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = PrintWriter(clientSocket.getOutputStream(), true)
            val output = clientSocket.getOutputStream()

            val requestLine = reader.readLine()
            println("Received request: $requestLine")

            if (requestLine == null) {
                return
            }

            val parts = requestLine.split(" ")
            if (parts.size != 3) {
                sendError(writer, 400, "Bad Request")
                return
            }

            val method = parts[0]
            val path = parts[1]

            if (method != "GET") {
                sendError(writer, 501, "Not Implemented")
                return
            }

            val filePath = config.documentRoot.resolve(path.substring(1)).normalize()
            println("Looking for static file at: $filePath")

            if (!filePath.startsWith(config.documentRoot)) {
                println("File path outside document root: $filePath")
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
                println("Static file not found, checking database for path: $path")
                val dbPath = config.sqliteDbPath
                println("Using database at: $dbPath")

                val db: SQLiteDatabase = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)

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
                    println("Found content in database")
                    var dbContent = cursor.getBlob(cursor.getColumnIndexOrThrow("content"))
                    val dbMimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type"))
                    val compression = cursor.getString(cursor.getColumnIndexOrThrow("compression"))

                    if (compression == "brotli") {
                        try {
                            println("Decompressing Brotli content")
                            dbContent = BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
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
                    Log.i("WebServer.kt", "No content found in database for path: $path")
                    sendError(writer, 404, "Not Found")
                }

                cursor.close()
                db.close()


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
            println("Error handling client: ${e.message}")
            sendError(PrintWriter(clientSocket.getOutputStream(), true), 500, "Internal Server Error")
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
        if (::serverThread.isInitialized) {
            serverThread.interrupt()
        }
        println("Server stopped")
    }
}
