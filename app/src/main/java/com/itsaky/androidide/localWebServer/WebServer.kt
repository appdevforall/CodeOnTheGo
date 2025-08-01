package com.itsaky.androidide.localWebServer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
// Is this needed? --DS, 25-Jul-2025
//import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale

/* To test this:

Check the logcat window to verify that the flow of control and variable values
look reasonable for the items below.

In the Termux terminal:

  curl -vi http://localhost/x.html

  curl -vi http://localhost/x.pdf

Using Chrome, repeat those URLs and verify that the results look reasonable.

Then in Chrome, try:

  http://localhost/p/web/viewer.html?file=/x.pdf

After a minute or less, you should see "Hello World" in the Chrome browser rendered by PDF.JS.

*/



data class ServerConfig(
    val port: Int = 6174,
    val databasePath: String,
    val bindName: String = "0.0.0.0", // TODO: Change to "localhost" --DS, 21-Jul-2025
    val debugDatabasePath: String = android.os.Environment.getExternalStorageDirectory()
        .toString() +
            "/Download/documentation.db",
    val applicationContext: Context
)

class WebServer(private val config: ServerConfig) {
    private lateinit var serverSocket: ServerSocket
    private lateinit var database: SQLiteDatabase
    private var databaseTimestamp: Long = -1
    private var brotliSupported = false
    private val TAG = "WebServer"
    private val encodingHeader: String = "Accept-Encoding"
    private val brotliCompression: String = "br"

    companion object {
        var method = ""
    }


    fun getDatabaseTimestamp(pathname: String, silent: Boolean = false): Long {
        val dbFile = File(pathname)
        var timestamp: Long = -1

        if (dbFile.exists()) {
            timestamp = dbFile.lastModified()

            if (!silent) {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                Log.d(
                    TAG,
                    "${pathname} was last modified at ${dateFormat.format(Date(timestamp))}."
                )
            }
        }

        return timestamp
    }

    fun start() {
        lateinit var clientSocket: Socket
        try {
            Log.d(TAG, "Starting WebServer on ${config.bindName}, port ${config.port}")

            databaseTimestamp = getDatabaseTimestamp(config.databasePath)

            try {
                database = SQLiteDatabase.openDatabase(
                    config.databasePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open database: ${e.message}")
                return
            }

            serverSocket =
                ServerSocket(config.port, 0, java.net.InetAddress.getByName(config.bindName))
            Log.i(TAG, "WebServer started successfully.")

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
        Log.d(TAG, "In handleClient(), socket=${clientSocket}.")
        val output = clientSocket.getOutputStream()
        val writer = PrintWriter(output, true)
        val inputStream = InputStreamReader(clientSocket.getInputStream())
        val reader = BufferedReader(inputStream)
        brotliSupported = false //assume nothing

        // Read the request line
        var requestLine = reader.readLine() ?: return
        Log.d(TAG, "  requestLine='${requestLine}'.")

        // Parse the request
        val parts = requestLine.split(" ")
        if (parts.size != 3) { // Request line should look like "GET /a/b/c.html HTTP/1.1"
            return sendError(writer, 400, "Bad Request")
        }

        method = parts[0]
        var path = parts[1].split("?")[0] // Discard any HTTP query parameters.
        path = path.substring(1)
        Log.d(TAG, "  method='$method', path='${path}', parts=${parts}.")

// ALEX: ADD SUPPORT FOR THE POST method. When you receive POST, read the content. Discard the header lines and the blank line. What follows will be the POSTed content. Verify all of this with logging. Then write the content to a temporary file, invoke a shell to run javac, capture the output in a file, and send it back in the same way the GET method sends content back. Use Content-Type: text/text and no compression.

        // Only support GET method
        if (method != "GET" && method != "POST") {
            return sendError(writer, 501, "Not Implemented")
        }
        var contentLength = 0
        //headers follow the Gte line read until eof or 0 length
        Log.d(TAG, "  encodingHeader='${encodingHeader}'.")
        while (requestLine.length > 0) {
            requestLine = reader.readLine() ?: break
            Log.d(TAG, "  requestLine='${requestLine}'.")
            if (requestLine.contains("Content-Length")) {
                contentLength = requestLine.split(" ")[1].toInt()
            }

            if (requestLine.startsWith(encodingHeader)) {
                val parts = requestLine.replace(" ", "").split(":")[1].split(",")
                Log.d(TAG, "  parts=${parts}.")
                if (parts.size == 0) {
                    break
                }
                brotliSupported = parts.contains(brotliCompression)
                Log.d(
                    TAG,
                    "brotliSupport=${brotliSupported}, brotliCompression='${brotliCompression}'."
                )
                break
            }
        }
        Log.d(TAG, "  brotliSupported=${brotliSupported}.")
        Log.d(TAG, "Method -> $method")
        if (method == "POST") {

            val data = CharArray(contentLength)

            var bytesRead = 0
            while (bytesRead < contentLength) {
                val readResult = reader.read(data, bytesRead, contentLength - bytesRead)
                if (readResult == -1) { // End of stream reached prematurely
                    Log.e(TAG, "POST data stream ended prematurely")
                    sendError(writer, 400, "Bad Request: Incomplete POST data")
                    return
                }
                bytesRead += readResult
            }

            Log.d(TAG, "Concat data = '${data}'")

            val file = createFileFromPost(data)
            val result = compileAndRunJava(file)
            val byteArrayFromResult = result.toByteArray()

            Log.d(TAG, String(byteArrayFromResult))

            val javacOut = String(data) + String(byteArrayFromResult) // Your existing line
            val javacOutBytes = javacOut.toByteArray(Charsets.UTF_8) // Encode to bytes

            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/plain; charset=utf-8") // Be explicit about charset
            writer.println("Content-Length: ${javacOutBytes.size}") // Use the byte array's size
            writer.println() // End of headers

            // Option A: Write bytes directly (more control, avoids extra newline from println)
            output.write(javacOutBytes)
            output.flush()
            writer.flush()

            return
        }

        val debugDatabaseTimestamp = getDatabaseTimestamp(config.debugDatabasePath, true)
        Log.d(
            TAG,
            "  debugDatabaseTimestamp=${debugDatabaseTimestamp}, databaseTimestamp=${databaseTimestamp}, delta=${debugDatabaseTimestamp - databaseTimestamp}."
        )
        if (debugDatabaseTimestamp > databaseTimestamp) {
            database.close()
            database = SQLiteDatabase.openDatabase(
                config.debugDatabasePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            databaseTimestamp = debugDatabaseTimestamp
        }
        // TODO: Get rid of the extra test in the SQL WHERE clause below by fixing all the paths. --DS, 22-Jul-2025
        val query = """
            SELECT c.content, ct.value, ct.compression
            FROM Content c
            JOIN ContentTypes ct ON c.contentTypeID = ct.id
            WHERE c.path = ? OR c.path = ?
            LIMIT 1
        """
        val cursor = database.rawQuery(query, arrayOf(path, path.substring(1)))
        val rowCount = cursor.getCount()
        Log.d(TAG, "  rowCount=${rowCount}.")

        if (rowCount != 1) {
            return if (rowCount == 0) sendError(writer, 404, "Not Found") else sendError(
                writer,
                406,
                "Not Acceptable"
            )
        }

        cursor.moveToFirst()
        var dbContent = cursor.getBlob(0)
        val dbMimeType = cursor.getString(1)
        var compression = cursor.getString(2)
        Log.d(
            TAG,
            "  dbContent length is ${dbContent.size}, dbMimeType='${dbMimeType}', compression=${compression}."
        )

        // If the Accept-Encoding header contains "br", the client can handle
        // Brotli. Send Brotli data as-is, without decompressing it here.
        // If the client can't handle Brotli, and the content is Brotli-
        // compressed, decompress the content here.

        if (compression == "brotli") {
            if (brotliSupported) {
                compression = "br"
            } else {
                Log.d(TAG, "  decompressing in the web server.")
                try {
                    dbContent =
                        BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                    compression = "none"
                    Log.d(TAG, "  length is now {dbContent.length}.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error decompressing Brotli content: ${e.message}")
                    return sendError(writer, 500, "Internal Server Error")
                }
            }
        }

        writer.println("HTTP/1.1 200 OK")
        writer.println("Content-Type: $dbMimeType")
        writer.println("Content-Length: ${dbContent.size}")

        if (compression != "none") {
            Log.d(TAG, "  Writing compression='${compression}'.")
            writer.println("Content-Encoding: ${compression}")
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

    private fun createFileFromPost(input: CharArray): File {
        val inputAsString = String(input)
        val filePath = "/storage/emulated/0/AndroidIDEProjects/My Application7/Playground.java"
        val file = File(filePath)
        try {
            file.writeText(inputAsString)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file")
        }
        return file
    }

    private fun compileAndRunJava(sourceFile: File): String {
        val dir = sourceFile.parentFile
        val fileName = sourceFile.nameWithoutExtension
        val classFile = File(dir, "$fileName.class")
        val filePath = "/storage/emulated/0/AndroidIDEProjects/Playground.java"
        val directoryPath = config.applicationContext.filesDir
        val javacPath = "$directoryPath/usr/bin/javac"
        val javaPath = "$directoryPath/usr/bin/java"

        val javac = ProcessBuilder(javacPath, filePath)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val compileOutput = javac.inputStream.bufferedReader().readText()
        javac.waitFor()

        if (!classFile.exists()) {
            return "Compilation failed:\n$compileOutput"
        }

        val java = ProcessBuilder(
            javaPath,
            "-cp",
            dir?.absolutePath,
            fileName
        )
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val runOutput = java.inputStream.bufferedReader().readText()
        java.waitFor()

        return if (compileOutput.isNotBlank()) {
            "Compile output\n $compileOutput\n Program output\n$runOutput"
        } else {
            "Program output\n $runOutput"
        }

    }
}
