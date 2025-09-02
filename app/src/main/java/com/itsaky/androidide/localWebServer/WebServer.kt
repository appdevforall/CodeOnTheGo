package org.appdevforall.localwebserver

import android.database.sqlite.SQLiteDatabase
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import org.slf4j.LoggerFactory
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
    private          val log = LoggerFactory.getLogger(WebServer::class.java)
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

                if (debugEnabled) log.debug("{} was last modified at {}.", pathname, dateFormat.format(Date(timestamp)))
            }
        }

        return timestamp
    }

    // NEW FEATURE: Log database last change information
    fun logDatabaseLastChanged() {
        try {
            val query = """
SELECT now, who
FROM   LastChange
"""
            val cursor = database.rawQuery(query, arrayOf())
            if (cursor.count > 0) {
                cursor.moveToFirst()
                log.debug("Database last change: {} {}.", cursor.getString(0), cursor.getString(1))
            }
            cursor.close()
        } catch (e: Exception) {
            log.debug("Could not retrieve database last change info: {}", e.message)
        }
    }

    fun start() {
        lateinit var clientSocket: Socket
        try {
            log.debug("Starting WebServer on {}, port {}, debugEnabled={}, debugEnablePath='{}', debugDatabasePath='{}'.", 
                     config.bindName, config.port, debugEnabled, config.debugEnablePath, config.debugDatabasePath)

            databaseTimestamp = getDatabaseTimestamp(config.databasePath)

            try {
                database = SQLiteDatabase.openDatabase(config.databasePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                log.error("Cannot open database: {}", e.message)
                return
            }

            // NEW FEATURE: Log database metadata when debug is enabled
            if (debugEnabled) logDatabaseLastChanged()

            serverSocket = ServerSocket(config.port, 0, java.net.InetAddress.getByName(config.bindName))
            log.info("WebServer started successfully.")

            while (true) {
                try {
                    clientSocket = serverSocket.accept()
                    if (debugEnabled) log.debug("Returned from socket accept().")
                    handleClient(clientSocket)
                } catch (e: Exception) {
                    log.error("Error handling client: {}", e.message)
                    try {
                        val writer = PrintWriter(clientSocket.getOutputStream(), true)
                        sendError(writer, 500, "Internal Server Error 1")
                    } catch (e: Exception) {
                        log.error("Error sending error response: {}", e.message)
                    }
                } finally {
                    clientSocket.close() // TODO: What if the client socket isn't open? How to check? --DS, 22-Jul-2025
                }
            }
        } catch (e: Exception) {
            log.error("Error: {}", e.message)
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
        if (debugEnabled) log.debug("Request is {}", requestLine)

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

           if (debugEnabled) log.debug("Header: {}", requestLine)

            if(requestLine.startsWith(encodingHeader)) {
                val parts = requestLine.replace(" ", "").split(":")[1].split(",")
                if(parts.size == 0) {
                    break
                }
                brotliSupported = parts.contains(brotliCompression)
                break
            }
        }

        //check to see if there is a newer version of the documentation.db database on the sdcard
        // if there is use that for our responses
        val debugDatabaseTimestamp = getDatabaseTimestamp(config.debugDatabasePath, true)
        if (debugDatabaseTimestamp > databaseTimestamp) {
            database.close()
            database = SQLiteDatabase.openDatabase(config.debugDatabasePath, null, SQLiteDatabase.OPEN_READONLY)
            databaseTimestamp = debugDatabaseTimestamp
        }

        val query = """
SELECT C.content, CT.value, CT.compression
FROM   Content C, ContentTypes CT
WHERE  C.contentTypeID = CT.id
  AND  C.path = ?
        """
        val cursor = database.rawQuery(query, arrayOf(path,))
        val rowCount = cursor.getCount()

        if (rowCount != 1) {
            return when (rowCount) {
                0 -> sendError(writer, 404, "Not Found", "Path requested: " + path)
                else -> sendError(writer, 500, "Internal Server Error 2", "Corrupt database - multiple records found when unique record expected, Path requested: " + path)
            }
        }

        cursor.moveToFirst()
        var dbContent   = cursor.getBlob(0)
        val dbMimeType  = cursor.getString(1)
        var compression = cursor.getString(2)

        if (debugEnabled) log.debug("len(content)={}, MIME type={}, compression={}.", dbContent.size, dbMimeType, compression)

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
                if (debugEnabled) log.debug("DB item > 1 MB. fragment#{} path2='{}'.", fragmentNumber, path2)

                val cursor2 = database.rawQuery(query2, arrayOf(path2))
                cursor2.moveToFirst()
                dbContent2 = cursor2.getBlob(0)
                dbContent += dbContent2 // TODO: Is there a faster way to do this? Is data being copied multiple times? --D.S., 22-Jul-2025
                fragmentNumber += 1
                if (debugEnabled) log.debug("Fragment size={}, dbContent.length={}.", dbContent2.size, dbContent.size)
            }
        }

        // If the Accept-Encoding header contains "br", the client can handle
        // Brotli. Send Brotli data as-is, without decompressing it here.
        // If the client can't handle Brotli, and the content is Brotli-
        // compressed, decompress the content here.

        if (compression == "brotli") {
            if (brotliSupported) {
                compression = "br"
            } else {
                try {
                    if (debugEnabled) log.debug("Brotli content but client doesn't support Brotli. Decoding locally.")
                    dbContent =
                        BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                    compression = "none"
                } catch (e: Exception) {
                    log.error("Error decompressing Brotli content: {}", e.message)
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
