package org.appdevforall.localwebserver

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Date
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat
import java.util.Locale
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ServerConfig(
    val port: Int = 6174,
    val databasePath: String,
    val bindName: String = "localhost",
    val debugDatabasePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/documentation.db",
    val debugEnablePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/CodeOnTheGo.webserver.debug",
    val fileDirPath: String
)

class WebServer(private val config: ServerConfig) {
    private lateinit var serverSocket: ServerSocket
    private lateinit var database: SQLiteDatabase
    private var databaseTimestamp: Long = -1
    private val log = LoggerFactory.getLogger(WebServer::class.java)
    private var debugEnabled: Boolean = File(config.debugEnablePath).exists()
    private val encodingHeader: String = "Accept-Encoding"
    private var brotliSupported = false
    private val brotliCompression: String = "br"
    private val playgroundFilePath = "${config.fileDirPath}/Playground.java"
    private val truncationLimit = 10000
    private val postDataFieldName = "data"
    private val playgroundEndpoint = "playground/execute"

    //function to obtain the last modified date of a documentation.db database
    // this is used to see if there is a newer version of the database on the sdcard
    fun getDatabaseTimestamp(pathname: String, silent: Boolean = false): Long {
        val dbFile = File(pathname)
        var timestamp: Long = -1

        if (dbFile.exists()) {
            timestamp = dbFile.lastModified()

            if (!silent) {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                if (debugEnabled) log.debug(
                    "{} was last modified at {}.",
                    pathname,
                    dateFormat.format(Date(timestamp))
                )
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
            log.debug(
                "Starting WebServer on {}, port {}, debugEnabled={}, debugEnablePath='{}', debugDatabasePath='{}'.",
                config.bindName,
                config.port,
                debugEnabled,
                config.debugEnablePath,
                config.debugDatabasePath
            )

            databaseTimestamp = getDatabaseTimestamp(config.databasePath)

            try {
                database = SQLiteDatabase.openDatabase(
                    config.databasePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
            } catch (e: Exception) {
                log.error("Cannot open database: {}", e.message)
                return
            }

            // NEW FEATURE: Log database metadata when debug is enabled
            if (debugEnabled) logDatabaseLastChanged()

            serverSocket =
                ServerSocket(config.port, 0, java.net.InetAddress.getByName(config.bindName))
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
        var path = parts[1].split("?")[0] // Discard any HTTP query parameters.
        path = path.substring(1)

        // we only support teh GET method, return an error page for anything else
        if (method != "GET" && method != "POST") {
            return sendError(writer, 501, "Not Implemented")
        }


        var contentLength = 0

        //the HTTP headers follow the the method line, read until eof or 0 length
        //if we encounter the Encoding Header, check to see if brotli encoding (br) is supported
        while (requestLine.length > 0) {
            requestLine = reader.readLine() ?: break
            if (requestLine.isEmpty()) {
                break
            }

            if (debugEnabled) log.debug("Header: {}", requestLine)

            if (requestLine.contains("Content-Length")) {
                contentLength = requestLine.split(" ")[1].toInt()
            }

            if (requestLine.startsWith(encodingHeader)) {
                val parts = requestLine.replace(" ", "").split(":")[1].split(",")
                brotliSupported = parts.contains(brotliCompression)
            }
        }

        if (method == "POST" && path != playgroundEndpoint) {
            log.debug("Received POST request to an invalid endpoint: " + path)
            return sendError(writer, 404, "Not found")
        }

        if (method == "POST" && path == playgroundEndpoint) {
            val data = CharArray(contentLength)

            var bytesRead = 0
            while (bytesRead < contentLength) {
                val readResult = reader.read(data, bytesRead, contentLength - bytesRead)
                if (readResult == -1) { // End of stream reached prematurely
                    log.debug("POST data stream ended prematurely")
                    sendError(writer, 400, "Bad Request: Incomplete POST data")
                    return
                }
                bytesRead += readResult
            }

            log.debug("Received data, length='${data.size}'")

            val file = createFileFromPost(data)

            // If no code data is received, tell client that their request was invalid
            if (file.length() == 0L) {
                return sendError(writer, 400, "Bad Request")
            }

            // Call the revised function that returns a data class

            // Inside the POST handler...
            val result = compileAndRunJava(file)

            // Dynamic Styling Logic
            val hasError = result.compileOutput.contains("error:", ignoreCase = true)
            val hasWarning = result.compileOutput.contains("warning:", ignoreCase = true) ||
                    result.compileOutput.contains("deprecation", ignoreCase = true)

            val (compBorderColor, compBorderWidth) = when {
                hasError -> "#ff0000" to "3px"
                hasWarning -> "#ffff00" to "3px"
                else -> "#00ff00" to "1px"
            }

            val runBorderColor = if (result.timedOut) "#ff0000" else "#00ff00"
            val runBorderWidth = if (result.timedOut) "3px" else "1px"

            val html = buildString {
                appendLine("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
                appendLine("<style>")
                appendLine("body { font-family: sans-serif; padding: 20px; background-color: #f8f9fa; font-size: 24px; }")
                appendLine("h2 { font-size: 28px; margin-top: 0; }")
                appendLine("pre, p, button { font-size: 24px; }")
                appendLine(".output-box { background-color: white; padding: 15px; border-radius: 5px; margin-bottom: 20px; overflow-x: auto; }")
                appendLine("#compiler-output-container { border: $compBorderWidth solid $compBorderColor; }")
                appendLine("#program-output-container { border: $runBorderWidth solid $runBorderColor; }")
                appendLine("pre { white-space: pre-wrap; word-wrap: break-word; margin: 0; }")
                appendLine("</style></head><body>")
                appendLine("<button onclick='window.history.back()'>Back to code editor</button>")

                // Program Output - Logic updated for timeout message
                appendLine("<div id='program-output-container' class='output-box'><h2>Program Output</h2>")

                val runOutputDisplay = when {
                    result.timedOut -> "Program timed out after ${result.timeoutLimit} seconds."
                    result.runOutput.isBlank() -> "No output."
                    else -> result.runOutput
                }

                appendLine("<pre id='program-output'>${escapeHtml(runOutputDisplay)}</pre></div>")

                // Compiler Output
                appendLine("<div id='compiler-output-container' class='output-box'><h2>Compiler Output</h2>")
                appendLine("<p style='margin: 0 0 10px 0; font-weight: bold;'>Time to compile: ${result.compileTimeMs}ms</p>")
                appendLine("<pre id='compiler-output'>${escapeHtml(result.compileOutput.ifBlank { "Compilation successful." })}</pre></div>")

                appendLine("</body></html>")
            }


            // Send the HTML response using the new helper function
            sendHtmlResponse(writer, output, html)
            return
        }

        // Check to see if there is a newer version of the documentation.db database on the sdcard
        // if there is use that for our responses
        val debugDatabaseTimestamp = getDatabaseTimestamp(config.debugDatabasePath, true)
        if (debugDatabaseTimestamp > databaseTimestamp) {
            database.close()
            database = SQLiteDatabase.openDatabase(
                config.debugDatabasePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            databaseTimestamp = debugDatabaseTimestamp
        }

        // Handle the special "/db" endpoint with highest priority
        if (path == "db") {
            return handleDbEndpoint(writer, output)
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
                else -> sendError(
                    writer,
                    500,
                    "Internal Server Error 2",
                    "Corrupt database - multiple records found when unique record expected, Path requested: " + path
                )
            }
        }

        cursor.moveToFirst()
        var dbContent = cursor.getBlob(0)
        val dbMimeType = cursor.getString(1)
        var compression = cursor.getString(2)

        if (debugEnabled) log.debug(
            "len(content)={}, MIME type={}, compression={}.",
            dbContent.size,
            dbMimeType,
            compression
        )

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
                if (debugEnabled) log.debug(
                    "DB item > 1 MB. fragment#{} path2='{}'.",
                    fragmentNumber,
                    path2
                )

                val cursor2 = database.rawQuery(query2, arrayOf(path2))
                cursor2.moveToFirst()
                dbContent2 = cursor2.getBlob(0)
                dbContent += dbContent2 // TODO: Is there a faster way to do this? Is data being copied multiple times? --D.S., 22-Jul-2025
                fragmentNumber += 1
                if (debugEnabled) log.debug(
                    "Fragment size={}, dbContent.length={}.",
                    dbContent2.size,
                    dbContent.size
                )
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

    private fun handleDbEndpoint(writer: PrintWriter, output: java.io.OutputStream) {
        try {
            // First, get the schema of the LastChange table to determine column count
            val schemaQuery = "PRAGMA table_info(LastChange)"
            val schemaCursor = database.rawQuery(schemaQuery, arrayOf())
            val columnCount = schemaCursor.count
            val columnNames = mutableListOf<String>()

            while (schemaCursor.moveToNext()) {
                columnNames.add(schemaCursor.getString(1)) // Column name is at index 1
            }
            schemaCursor.close()

            if (debugEnabled) log.debug(
                "LastChange table has {} columns: {}",
                columnCount,
                columnNames
            )

            // Build the SELECT query for the 20 most recent rows
            val selectColumns = columnNames.joinToString(", ")
            val dataQuery = "SELECT $selectColumns FROM LastChange ORDER BY rowid DESC LIMIT 20"

            val dataCursor = database.rawQuery(dataQuery, arrayOf())
            val rowCount = dataCursor.count

            if (debugEnabled) log.debug("Retrieved {} rows from LastChange table", rowCount)

            // Generate HTML table
            val html = buildString {
                appendLine("<!DOCTYPE html>")
                appendLine("<html>")
                appendLine("<head>")
                appendLine("<title>LastChange Table</title>")
                appendLine("<style>")
                appendLine("table { border-collapse: collapse; width: 100%; }")
                appendLine("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }")
                appendLine("th { background-color: #f2f2f2; }")
                appendLine("</style>")
                appendLine("</head>")
                appendLine("<body>")
                appendLine("<h1>LastChange Table (20 Most Recent Rows)</h1>")
                appendLine("<table width='100%'>")

                // Add header row
                appendLine("<tr>")
                for (columnName in columnNames) {
                    appendLine("<th>${escapeHtml(columnName)}</th>")
                }
                appendLine("</tr>")

                // Add data rows
                while (dataCursor.moveToNext()) {
                    appendLine("<tr>")
                    for (i in 0 until columnCount) {
                        val value = dataCursor.getString(i) ?: ""
                        appendLine("<td>${escapeHtml(value)}</td>")
                    }
                    appendLine("</tr>")
                }

                appendLine("</table>")
                appendLine("</body>")
                appendLine("</html>")
            }

            dataCursor.close()

            // Send the response
            val htmlBytes = html.toByteArray(Charsets.UTF_8)
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/html; charset=utf-8")
            writer.println("Content-Length: ${htmlBytes.size}")
            writer.println("Connection: close")
            writer.println()
            writer.flush()
            output.write(htmlBytes)
            output.flush()

        } catch (e: Exception) {
            log.error("Error handling /db endpoint: {}", e.message)
            sendError(
                writer,
                500,
                "Internal Server Error",
                "Error generating database table: ${e.message}"
            )
        }
    }

    /**
     * Escapes HTML special characters to prevent XSS attacks.
     * Converts <, >, &, ", and ' to their HTML entity equivalents.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")   // Must be first to avoid double-escaping
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
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


    private fun createFileFromPost(input: CharArray): File {
        val decoded =
            URLDecoder.decode(String(input), StandardCharsets.UTF_8.name())

        val inputAsString = if (decoded.startsWith(postDataFieldName)) {
            // Add 1 to substring start index to handle the "=" between the POST data
            // field name and the POST data field data.
            decoded.substring(postDataFieldName.length + 1)
        } else{
            log.warn("Expecting a data field named " + postDataFieldName)
            ""
        }

        val file = File(playgroundFilePath)
        try {
            file.writeText(inputAsString)
        } catch (e: Exception) {
            log.debug("Error creating file")
        }
        return file
    }

private fun compileAndRunJava(sourceFile: File): JavaExecutionResult {
    val dir = sourceFile.parentFile
    val fileName = sourceFile.nameWithoutExtension
    val classFile = File(dir, "$fileName.class")
    val javacPath = "${config.fileDirPath}/usr/bin/javac"
    val javaPath = "${config.fileDirPath}/usr/bin/java"
    val timeoutSeconds = 5L

    // Compilation Timing
    val startTime = System.currentTimeMillis()
    val javac = ProcessBuilder(javacPath, sourceFile.absolutePath)
        .directory(dir).redirectErrorStream(true).start()

    val compileOutputBuilder = StringBuilder()
    val javacGobbler = Thread {
        try { javac.inputStream.bufferedReader().forEachLine { compileOutputBuilder.appendLine(it) } } catch (_: IOException) {}
    }.apply { start() }

    val didJavacFinish = javac.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    val endTime = System.currentTimeMillis()
    val compileTime = endTime - startTime

    if (!didJavacFinish) javac.destroyForcibly()
    javacGobbler.join(1000)

    var compileOutput = compileOutputBuilder.toString()
    if (compileOutput.length > truncationLimit) compileOutput = compileOutput.substring(0, truncationLimit) + " [Truncated]"

    if (!classFile.exists()) {
        return JavaExecutionResult(compileOutput, "Execution skipped.", !didJavacFinish, compileTime, timeoutSeconds)
    }

    // Execution
    val java = ProcessBuilder(javaPath, "-cp", dir.absolutePath, fileName)
        .directory(dir).redirectErrorStream(true).start()

    val runOutputBuilder = StringBuilder()
    val javaGobbler = Thread {
        try { java.inputStream.bufferedReader().forEachLine { runOutputBuilder.appendLine(it) } } catch (_: IOException) {}
    }.apply { start() }

    val didJavaFinish = java.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!didJavaFinish) java.destroyForcibly()
    javaGobbler.join(1000)

    var runOutput = runOutputBuilder.toString()
    if (runOutput.length > truncationLimit) runOutput = runOutput.substring(0, truncationLimit) + " [Truncated]"

    Files.deleteIfExists(Paths.get(classFile.path))

    return JavaExecutionResult(compileOutput, runOutput, !didJavaFinish, compileTime, timeoutSeconds)
}

    data class JavaExecutionResult(
        val compileOutput: String,
        val runOutput: String,
        val timedOut: Boolean,
        val compileTimeMs: Long,
        val timeoutLimit: Long
    )

    private fun sendHtmlResponse(
        writer: PrintWriter,
        output: java.io.OutputStream,
        htmlContent: String,
        code: Int = 200,
        message: String = "OK"
    ) {
        val htmlBytes = htmlContent.toByteArray(Charsets.UTF_8)
        writer.println("HTTP/1.1 $code $message")
        writer.println("Content-Type: text/html; charset=utf-8")
        writer.println("Content-Length: ${htmlBytes.size}")
        writer.println("Connection: close")
        writer.println()
        writer.flush()
        output.write(htmlBytes)
        output.flush()
    }
}

