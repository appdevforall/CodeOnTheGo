package org.appdevforall.localwebserver

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket


data class ServerConfig(
    val port: Int = 6174,
    val databasePath: String = "/data/data/com.itsaky.androidide/databases/documentation.db"
)

class WebServer(private val config: ServerConfig) {
    private var running = false
    private lateinit var serverSocket: ServerSocket
    private val TAG = "WebServer"

    fun start() {
        try {
            Log.d(TAG, "Starting WebServer on port ${config.port}")
            
            // Verify database access
            try {
                val testDb = SQLiteDatabase.openDatabase(config.databasePath, null, SQLiteDatabase.OPEN_READONLY)
                testDb.close()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open database: ${e.message}")
                return
            }

            serverSocket = ServerSocket(config.port, 0, java.net.InetAddress.getByName("0.0.0.0"))
            running = true
            Log.i(TAG, "WebServer started successfully on port ${config.port}")
            
            while (running) {
                val clientSocket = serverSocket.accept()
                handleClient(clientSocket)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
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

            val db = SQLiteDatabase.openDatabase(config.databasePath, null, SQLiteDatabase.OPEN_READONLY)
            val query = """
                SELECT c.content, ct.value AS mime_type, ct.compression
                FROM Content c
                JOIN ContentTypes ct ON c.contentTypeID = ct.id
                WHERE c.path = ? OR c.path = ?
                LIMIT 1
            """
            val cursor = db.rawQuery(query, arrayOf(path, path.substring(1)))
            if (cursor.moveToFirst()) {
                var dbContent = cursor.getBlob(cursor.getColumnIndexOrThrow("content"))
                val dbMimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type"))
                val compression = cursor.getString(cursor.getColumnIndexOrThrow("compression"))
                    
                // If content is Brotli compressed, decompress it
                if (compression == "brotli") {
                    try {
                        dbContent = BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decompressing Brotli content: ${e.message}")
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
            } else {
                sendError(writer, 404, "Not Found")
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
            try {
                val writer = PrintWriter(clientSocket.getOutputStream(), true)
                sendError(writer, 500, "Internal Server Error")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending error response: ${e.message}")
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
    }
} 
