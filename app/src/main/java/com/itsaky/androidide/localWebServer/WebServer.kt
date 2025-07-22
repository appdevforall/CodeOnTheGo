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
    val port: Int = 8888,
    val databasePath: String,
    val bindName: String = "0.0.0.0", // TODO: Change to "localhost" --DS, 21-Jul-2025
    val debugDatabasePath: String = "/sdcard/Download/documentation.db"
)

class WebServer(private val config: ServerConfig) {
    private lateinit var serverSocket: ServerSocket
    private lateinit var database: SQLiteDatabase
    private var databaseTimestamp: Long = -1
    private var debugDatabaseTimestamp: Long = -1
    private val TAG = "WebServer"

    fun start() {
        lateinit var clientSocket: Socket
        try {
            Log.d(TAG, "Starting WebServer on port ${config.port}")

            // Verify database access
            try {
                database = SQLiteDatabase.openDatabase(config.databasePath, null, SQLiteDatabase.OPEN_READONLY)
                //databaseTimestamp = THE MODIFICATION TIME OF THE FILE AT config.databasePath // TODO: Write this code.
                val dbFile = File(config.debugDatabasePath)
                if(dbFile.exists()) {
                    debugDatabaseTimestamp = dbFile.lastModified()
                    // Convert milliseconds to a readable date and time string
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val lastModifiedDate = Date(debugDatabaseTimestamp)
                    val formattedDate = dateFormat.format(lastModifiedDate)
                    Log.d(
                        TAG,
                        "${config.databasePath} was last modified at $formattedDate"
                    ) //for debugging only
                } else {
                    //do nothing
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open database: ${e.message}")
                return
            }

            serverSocket = ServerSocket(config.port, 0, java.net.InetAddress.getByName(config.bindName))
            Log.i(TAG, "WebServer started successfully on port ${config.port}")

            while (true) {
                try {
                    clientSocket = serverSocket.accept()
                    handleClient(clientSocket)
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
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        } finally {
            if (::serverSocket.isInitialized) {
                serverSocket.close()
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
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
        val pathParts = path.split("?")
        // TODO: Split on ? and keep just the first part. --DS, 21-Jul-2025
        //?file=foo.pdf.....

        // Only support GET method
        if (method != "GET") {
            sendError(writer, 501, "Not Implemented")
            return
        }

        // TODO: Implement the code below. --DS, 21-Jul-2025
          //THE MODIFICATION TIME OF THE FILE AT config.debugDatabasePath
//      if (debugDatabaseTimestamp > databaseTimestamp) {
//          database.close()
//          database = SQLiteDatabase.openDatabase(config.debugDatabasePath, null, SQLiteDatabase.OPEN_READONLY)
//          databaseTimestamp = debugDatabaseTimestamp
//      }
        val query = """
            SELECT c.content, ct.value, ct.compression
            FROM Content c
            JOIN ContentTypes ct ON c.contentTypeID = ct.id
            WHERE c.path = ? OR c.path = ?
            LIMIT 1
        """
        val cursor = database.rawQuery(query, arrayOf(path, path.substring(1)))
        val rowCount = cursor.getCount()

        if (rowCount != 1) {
            if (rowCount == 0) {
                sendError(writer, 404, "Not Found")
            } else {
                sendError(writer, 406, "Not Acceptable")
            }
            return
        }

        cursor.moveToFirst()
        var dbContent = cursor.getBlob(0)
        val dbMimeType = cursor.getString(1)
        var compression = cursor.getString(2)

        // If content is Brotli compressed, decompress it
        if (compression == "brotli") {
            try {
                dbContent = BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                compression = "none"
            } catch (e: Exception) {
                Log.e(TAG, "Error decompressing Brotli content: ${e.message}")
                sendError(writer, 500, "Internal Server Error")
                return
            }
        }

        writer.println("HTTP/1.1 200 OK")
        writer.println("Content-Type: $dbMimeType")
        writer.println("Content-Length: ${dbContent.size}")

        if (compression == "brotli") {
            writer.println("Content-Encoding: br")
        }

        writer.println("Connection: close")
        writer.println()
        writer.flush()
        output.write(dbContent)
        output.flush()
        cursor.close()
    }

    private fun sendError(writer: PrintWriter, code: Int, message: String) {
        writer.println("HTTP/1.1 $code $message")
        writer.println("Content-Type: text/plain")
        writer.println("Connection: close")
        writer.println()
        writer.println("$code $message")
    }
}