package org.appdevforall.localwebserver

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale
data class ServerConfig(
    val port: Int = 6174,
    val databasePath: String,
    val bindName: String = "localhost",
    val debugDatabasePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/documentation.db",
    val debugEnablePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/CodeOnTheGo.webserver.debug"
)

class WebServer(private val config: ServerConfig) {
    private lateinit var serverSocket: ServerSocket
    private lateinit var database: SQLiteDatabase
    private          var databaseTimestamp: Long = -1
    private          val TAG = "WebServer"
    private          var debugEnabled: Boolean = File(config.debugEnablePath).exists()
    private          val encodingHeader : String = "Accept-Encoding"
    private          var brotliSupported = false
    private          val brotliCompression : String = "br"


    //function to obtain the last modified date of a documentation.db database
    // this is used to see if there is a newer version of the database on the sdcard
    fun getDatabaseTimestamp(pathname: String, silent: Boolean = false): Long {
        val dbFile = File(pathname)
        var timestamp: Long = -1

        if (dbFile.exists()) {
            timestamp = dbFile.lastModified()

            if (!silent) {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                if (debugEnabled) Log.d(TAG, "${pathname} was last modified at ${dateFormat.format(Date(timestamp))}.")
            }
        }

        return timestamp
    }

    fun logDatabaseLastChanged() {
        val query = """
SELECT now, who
FROM   LastChange
"""
        val cursor = database.rawQuery(query, arrayOf())
        cursor.moveToFirst()
        Log.d(TAG, "Database last change: ${cursor.getString(0)} ${cursor.getString(1)}.")
    }

    fun start() {
        lateinit var clientSocket: Socket
        try {
            Log.d(TAG, "Starting WebServer on ${config.bindName}, port ${config.port}, debugEnabled=${debugEnabled}, debugEnablePath='${config.debugEnablePath}', debugDatabasePath='${config.debugDatabasePath}'.")

            databaseTimestamp = getDatabaseTimestamp(config.databasePath)

            try {
                database = SQLiteDatabase.openDatabase(config.databasePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open database: ${e.message}")
                return
            }
            if (debugEnabled) logDatabaseLastChanged()

            serverSocket = ServerSocket(config.port, 0, java.net.InetAddress.getByName(config.bindName))
            Log.i(TAG, "WebServer started successfully.")

            while (true) {
                try {
                    clientSocket = serverSocket.accept()
                    if (debugEnabled) Log.d(TAG, "Returned from socket accept().")
                    handleClient(clientSocket)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling client: ${e.message}")
                    try {
                        val writer = PrintWriter(clientSocket.getOutputStream(), true)
                        sendError(writer, 500, "Internal Server Error 1")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending error response: ${e.message}")
                    }
                } finally {
                    clientSocket.close() // TODO: What if the client socket isn't open? How to check? --DS, 22-Jul-2025
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        } finally {
            if (::serverSocket.isInitialized) {
                serverSocket.close()
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        val output = clientSocket.getOutputStream()
        val writer = PrintWriter(output, true)
        val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
        brotliSupported = false //assume nothing

        // Read the request method line, it is always the first line of the request
        var requestLine = reader.readLine() ?: return
        if (debugEnabled) Log.d(TAG, "Request is '${requestLine}'.")

        // Parse the request
        // Request line should look like "GET /a/b/c.html HTTP/1.1"
        val parts = requestLine.split(" ")
        if (parts.size != 3) {
            return sendError(writer, 400, "Bad Request")
        }

        //extract the request method (e.g. GET, POST, PUT)
        val method = parts[0]
        var path   = parts[1].split("?")[0] // Discard any HTTP query parameters.
        path = path.substring(1)

        // we only support teh GET method, return an error page for anything else
        if (method != "GET") {
            return sendError(writer, 501, "Not Implemented")
        }

        //the HTTP headers follow the the method line, read until eof or 0 length
        //if we encounter the Encoding Header, check to see if brotli encoding (br) is supported
        while(requestLine.length > 0) {
            requestLine = reader.readLine() ?: break

           if (debugEnabled) Log.d(TAG, "Header: '${requestLine}'")

            if(requestLine.startsWith(encodingHeader)) {
                val parts = requestLine.replace(" ", "").split(":")[1].split(",")
                if(parts.size == 0) {
                    break
                }
                brotliSupported = parts.contains(brotliCompression)
                break
            }
        }

        // If there is a newer download of the documentation.db database on the sdcard, use it.
        val debugDatabaseTimestamp = getDatabaseTimestamp(config.debugDatabasePath, true)
        if (debugDatabaseTimestamp > databaseTimestamp) {
            database.close()
            database = SQLiteDatabase.openDatabase(config.debugDatabasePath, null, SQLiteDatabase.OPEN_READONLY)
            databaseTimestamp = debugDatabaseTimestamp
            if (debugEnabled) logDatabaseLastChanged()
        }

        val query = """
SELECT C.content, CT.value, CT.compression
FROM   Content C, ContentTypes CT
WHERE  C.contentTypeID = CT.id
  AND  C.path = ?
  AND  C.languageId = 1
        """
        var cursor = database.rawQuery(query, arrayOf(path,))
        val rowCount = cursor.getCount()

        if (rowCount != 1) {
            return when (rowCount) {
                0    -> sendError(writer, 404, "Not Found", "Path: '" + path + "'.")
                else -> sendError(writer, 500, "Internal Server Error 2", "Data error: multiple records found when unique record expected. Path: '" + path + "'.")
            }
        }

        cursor.moveToFirst()
        var dbContent   = cursor.getBlob(0)
        val dbMimeType  = cursor.getString(1)
        var compression = cursor.getString(2)

        if (dbContent.size == 1024 * 1024) { // Could use fragmentation to satisfy range requests.
            val query2 = """
SELECT content
FROM   Content
WHERE  path = ?
  AND  languageId = 1
        """
            var fragmentNumber = 1
            var dbContent2 = dbContent

            while (dbContent2.size == 1024 * 1024) {
                val path2 = "${path}-${fragmentNumber}"
                if (debugEnabled) Log.d(TAG, "DB item > 1 MB. fragment#${fragmentNumber}, path2='${path2}'.")

                cursor = database.rawQuery(query2, arrayOf(path2))
                cursor.moveToFirst()
                dbContent2 = cursor.getBlob(0)
                dbContent += dbContent2 // TODO: Is there a faster way to do this? Is data being copied multiple times? --D.S., 30-Aug-2025
                fragmentNumber += 1
                if (debugEnabled) Log.d(TAG, "Fragment size=${dbContent2.size}, dbContent.length=${dbContent.size}.")
            }
        }

        if (debugEnabled) Log.d(TAG, "len(content)=${dbContent.size}, MIME type=${dbMimeType}, compression=${compression}.")

        // If the Accept-Encoding header contains "br", the client can handle
        // Brotli. Send Brotli data as-is, without decompressing it here.
        // If the client can't handle Brotli, and the content is Brotli-
        // compressed, decompress the content here.

        if (compression == "brotli") {

            if (brotliSupported) {
                compression = "br"
            } else {
                try {
                    if (debugEnabled) Log.d(TAG, "Brotli content but client doesn't support Brotli. Decoding locally.")
                    dbContent =
                        BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                    compression = "none"
                } catch (e: Exception) {
                    Log.e(TAG, "Error decompressing Brotli content: ${e.message}")
                    return sendError(writer, 500, "Internal Server Error")
                }
            }
        }

        //send our response
        writer.println("HTTP/1.1 200 OK")
        writer.println("Content-Type: $dbMimeType")
        writer.println("Content-Length: ${dbContent.size}")

        if (compression != "none") {
            writer.println("Content-Encoding: ${compression}")
        }

        writer.println("Connection: close")
        writer.println()
        writer.flush()
        output.write(dbContent)
        output.flush()
        cursor.close()
    }

    private fun sendError(writer: PrintWriter, code: Int, message: String, details: String = "") {
        writer.println("HTTP/1.1 $code $message")
        writer.println("Content-Type: text/plain")
        writer.println("Connection: close")
        writer.println()
        writer.println("$code $message")
        if (details.isNotEmpty()) {
            writer.println(details)
        }
    }
}
