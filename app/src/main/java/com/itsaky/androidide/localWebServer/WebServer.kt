package org.appdevforall.localwebserver

import android.database.sqlite.SQLiteDatabase
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit;
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;


data class ServerConfig(
    val port: Int = 6174,
    val databasePath: String,
    val bindName: String = "localhost",
    val debugDatabasePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/documentation.db",
    val debugEnablePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/CodeOnTheGo.webserver.debug",
    val experimentsEnablePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/CodeOnTheGo.exp", // TODO: Centralize this concept. --DS, 9-Feb-2026

// Yes, this is hack code.
    val projectDatabasePath: String = "/data/data/com.itsaky.androidide/databases/RecentProject_database",
    val fileDirPath: String
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

                if (debugEnabled) log.debug("{} was last modified at {}.", pathname, dateFormat.format(Date(timestamp)))
            }
        }

        return timestamp
    }

    fun logDatabaseLastChanged() {
        try {
            val query = """
SELECT changeTime, who
FROM   LastChange
"""
            val cursor = database.rawQuery(query, arrayOf())

            try {
                if (cursor.count > 0) {
                    cursor.moveToFirst()
                    log.debug("Database last change: {} {}.", cursor.getString(0), cursor.getString(1))
                }

            } finally {
                cursor.close()
            }

        } catch (e: Exception) {
            log.error("Could not retrieve database last change info: {}", e.message)
        }
    }

    /**
     * Stops the server by closing the listening socket. Safe to call from any thread.
     * Causes [start]'s accept loop to exit. No-op if not started or already stopped.
     */
    fun stop() {
        if (!::serverSocket.isInitialized) return
        try {
            serverSocket.close()

        } catch (e: Exception) {
            log.error("Cannot close server socket: {}", e.message)
        }
    }

    fun start() {
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

            serverSocket = ServerSocket().apply { reuseAddress = true }
            serverSocket.bind(InetSocketAddress(config.bindName, config.port))
            log.info("WebServer started successfully.")

            while (true) {
                var clientSocket: Socket? = null
                try {
                    try {
                        clientSocket = serverSocket.accept()
                        if (debugEnabled) log.debug("Returned from socket accept().")
                    } catch (e: java.net.SocketException) {
                        if (e.message?.contains("Closed", ignoreCase = true) == true) {
                            if (debugEnabled) log.debug("WebServer socket closed, shutting down.")
                            break
                        }
                        log.error("Accept() failed: {}", e.message)
                        continue
                    }
                    try {
                        clientSocket?.let { handleClient(it) }
                    } catch (e: Exception) {
                        if (e is java.net.SocketException && e.message?.contains("Closed", ignoreCase = true) == true) {
                            if (debugEnabled) log.debug("Client disconnected: {}", e.message)
                        } else {
                            log.error("Error handling client: {}", e.message)
                            clientSocket?.let { socket ->
                                try {
                                    sendError(PrintWriter(socket.getOutputStream(), true), 500, "Internal Server Error 1")

                                } catch (e2: Exception) {
                                    log.error("Error sending error response: {}", e2.message)
                                }
                            }
                        }
                    }

                } finally {
                    clientSocket?.close()
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
        while (requestLine.isNotEmpty()) {
            requestLine = reader.readLine() ?: break

            if (debugEnabled) log.debug("Header: {}", requestLine)

            if (requestLine.startsWith(encodingHeader)) {
                val parts = requestLine.replace(" ", "").split(":")[1].split(",")

                brotliSupported = parts.contains(brotliCompression)
            }

            if (requestLine.contains("Content-Length")) {
                contentLength = requestLine.split(" ")[1].toInt()
            }
        }
        val maxContentLength = 1000000 // 1 MB limit for playground code
        if (method == "POST" && path == playgroundEndpoint) {

            if (contentLength <= 0 || contentLength > maxContentLength) {
                return sendError(writer, 413, "Payload Too Large")
            }
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


            //check to see if there is a newer version of the documentation.db database on the sdcard
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

            // Handle the special "pr" endpoint with highest priority
            if (path.startsWith("pr/", false)) {
                if (debugEnabled) log.debug("Found a pr/ path, '$path'.")

                return when (path) {
                    "pr/db" -> handleDbEndpoint(writer, output)
                    "pr/pr" -> handlePrEndpoint(writer, output)
                    "pr/ex" -> handleExEndpoint(writer, output)
                    else -> sendError(writer, 404, "Not Found")
                }
            }

            val query = """
SELECT C.content, CT.value, CT.compression
FROM   Content C, ContentTypes CT
WHERE  C.contentTypeID = CT.id
  AND  C.path = ?
        """
            val cursor = database.rawQuery(query, arrayOf(path))
            val rowCount = cursor.count

            var dbContent: ByteArray
            var dbMimeType: String
            var compression: String

            try {
                if (rowCount != 1 && path != playgroundEndpoint) {
                    return when (rowCount) {
                        0 -> sendError(writer, 404, "Not Found", "Path requested: $path")
                        else -> sendError(
                            writer,
                            500,
                            "Internal Server Error 2",
                            "Corrupt database - multiple records found when unique record expected, Path requested: $path"
                        )
                    }
                }

                cursor.moveToFirst()
                dbContent = cursor.getBlob(0)
                dbMimeType = cursor.getString(1)
                compression = cursor.getString(2)

                if (debugEnabled) log.debug(
                    "len(content)={}, MIME type={}, compression={}.",
                    dbContent.size,
                    dbMimeType,
                    compression
                )

            } finally {
                cursor.close()
            }

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
                    val path2 = "$path-$fragmentNumber"
                    if (debugEnabled) log.debug(
                        "DB item > 1 MB. fragment#{} path2='{}'.",
                        fragmentNumber,
                        path2
                    )

                    val cursor2 = database.rawQuery(query2, arrayOf(path2))
                    try {
                        if (cursor2.moveToFirst()) {
                            dbContent2 = cursor2.getBlob(0)

                        } else {
                            log.error("No fragment found for path '{}'.", path2)
                            break
                        }

                    } finally {
                        cursor2.close()
                    }

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
                        return sendError(writer, 500, "Internal Server Error 3")
                    }
                }
            }

            //send our response
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: $dbMimeType")
            writer.println("Content-Length: ${dbContent.size}")

            if (compression != "none") {
                writer.println("Content-Encoding: $compression")
            }

            writer.println("Connection: close")
            writer.println()
            writer.flush()
            output.write(dbContent)
            output.flush()
        }

    private fun handleDbEndpoint(writer: PrintWriter, output: java.io.OutputStream) {
        if (debugEnabled) log.debug("Entering handleDbEndpoint().")

        try {
            // First, get the schema of the LastChange table to determine column count
            val schemaQuery = "PRAGMA table_info(LastChange)"
            val schemaCursor = database.rawQuery(schemaQuery, arrayOf())

            var columnCount   : Int
            var selectColumns : String

            var html = getTableHtml("LastChange Table", "LastChange Table (20 Most Recent Rows)")

            try {
                columnCount = schemaCursor.count
                val columnNames = mutableListOf<String>()

                while (schemaCursor.moveToNext()) {
                    // Values come from schema introspection, therefore not subject to a SQL injection attack.
                    columnNames.add(schemaCursor.getString(1)) // Column name is at index 1
                }

                if (debugEnabled) log.debug("LastChange table has {} columns: {}", columnCount, columnNames)

                // Build the SELECT query for the 20 most recent rows
                selectColumns = columnNames.joinToString(", ")

                // Add header row
                html += """<tr>"""
                for (columnName in columnNames) {
                    html += """<th>${escapeHtml(columnName)}</th>"""
                }
                html += """</tr>"""

            } finally {
                schemaCursor.close()
            }

            val dataQuery = "SELECT $selectColumns FROM LastChange ORDER BY changeTime DESC LIMIT 20"

            val dataCursor = database.rawQuery(dataQuery, arrayOf())

            try {
                val rowCount = dataCursor.count

                if (debugEnabled) log.debug("Retrieved {} rows from LastChange table", rowCount)

                // Add data rows
                while (dataCursor.moveToNext()) {
                    html += """<tr>"""
                    for (i in 0 until columnCount) {
                        html += """<td>${escapeHtml(dataCursor.getString(i) ?: "")}</td>"""
                    }
                    html += """</tr>"""
                }

                html += """</table></body></html>"""

            } finally {
                dataCursor.close()
            }

            if (debugEnabled) log.debug("html is '$html'.")

            writeNormalToClient(writer, output, html)

            if (debugEnabled) log.debug("Leaving handleDbEndpoint().")

        } catch (e: Exception) {
            log.error("Error handling /pr/db endpoint: {}", e.message)
            sendError(writer, 500, "Internal Server Error 4", "Error generating database table.")
        }
    }

    private fun handleExEndpoint(writer: PrintWriter, output: java.io.OutputStream) {
        if (debugEnabled) log.debug("Entering handleExEndpoint().")

        // TODO: Use the centralized experiments flag instead of this ad-hoc check. --DS, 10-Feb-2026
        if (File(config.experimentsEnablePath).exists()) {
            if (debugEnabled) log.debug("Experimental mode is on. Returning 200.")

            writeNormalToClient(writer, output, """<html><head><title>Experiments</title></head><body>1</body></html>""")

        } else {
            if (debugEnabled) log.debug("Experimental mode is off. Returning 404.")

            sendError(writer, 404, "Not Found", "Experiments disabled")
        }

        if (debugEnabled) log.debug("Leaving handleExEndpoint().")
    }

    private fun handlePrEndpoint(writer: PrintWriter, output: java.io.OutputStream) {
        if (debugEnabled) log.debug("Entering handlePrEndpoint().")

        var projectDatabase : SQLiteDatabase? = null

        try {
            projectDatabase = SQLiteDatabase.openDatabase(config.projectDatabasePath,
                null,
                SQLiteDatabase.OPEN_READONLY)

            if (projectDatabase == null) {
                log.error("Error handling /pr/pr endpoint 2. Could not open ${config.projectDatabasePath}.")
                sendError(writer, 500, "Internal Server Error 5", "Error accessing database 2")

            } else {
                realHandlePrEndpoint(writer, output, projectDatabase)
            }

        } catch (e: Exception) {
            log.error("Error handling /pr/pr endpoint: {}", e.message)
            sendError(writer, 500, "Internal Server Error 6", "Error generating database table.")

        } finally {
            projectDatabase?.close()
        }

        if (debugEnabled) log.debug("Leaving handlePrEndpoint().")
    }

    private fun realHandlePrEndpoint(writer: PrintWriter, output: java.io.OutputStream, projectDatabase: SQLiteDatabase) {
        if (debugEnabled) log.debug("Entering realHandlePrEndpoint().")

        val query = """
SELECT id,
       name,
       DATETIME(create_at     / 1000, 'unixepoch'),
       DATETIME(last_modified / 1000, 'unixepoch'),
       location,
       template_name,
       language
FROM     recent_project_table
ORDER BY last_modified DESC"""

        var html = getTableHtml("Projects", "Projects") + """
<tr>
<th>Id</th>
<th>Name</th>
<th>Created</th>
<th>Modified &nbsp;&nbsp;<span style="font-family: sans-serif">V</span></th>
<th>Directory</th>
<th>Template</th>
<th>Language</th>
</tr>"""

        val cursor = projectDatabase.rawQuery(query, arrayOf())

        try {
            if (debugEnabled) log.debug("Retrieved {} rows.", cursor.count)

            while (cursor.moveToNext()) {
                html += """<tr>
<td>${escapeHtml(cursor.getString(0) ?: "")}</td>
<td>${escapeHtml(cursor.getString(1) ?: "")}</td>
<td>${escapeHtml(cursor.getString(2) ?: "")}</td>
<td>${escapeHtml(cursor.getString(3) ?: "")}</td>
<td>${escapeHtml(cursor.getString(4) ?: "")}</td>
<td>${escapeHtml(cursor.getString(5) ?: "")}</td>
<td>${escapeHtml(cursor.getString(6) ?: "")}</td>
</tr>"""
            }

            html += "</table></body></html>"

        } finally {
            cursor.close()
        }

        if (debugEnabled) log.debug("html is '$html'.")

        writeNormalToClient(writer, output, html)

        if (debugEnabled) log.debug("Leaving realHandlePrEndpoint().")
    }

    /**
     * Get HTML for table response page.
     */
    private fun getTableHtml(title: String, tableName: String): String {
        if (debugEnabled) log.debug("Entering getTableHtml(), title='$title', tableName='$tableName'.")

        return """<!DOCTYPE html>
<html>
<head>
<title>${escapeHtml(title)}</title>
<style>
table { border-collapse: collapse; width: 100%; }
th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
th { background-color: #f2f2f2; }
</style>
</head>
<body>
<h1>${escapeHtml(tableName)}</h1>
<table width='100%'>"""
    }

    /**
     * Tail of writing table data back to client.
     */
    private fun writeNormalToClient(writer: PrintWriter, output: java.io.OutputStream, html: String) {
        if (debugEnabled) log.debug("Entering writeNormalToClient(), html='$html'.")

        val htmlBytes = html.toByteArray(Charsets.UTF_8)

        writer.println("""HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Content-Length: ${htmlBytes.size}
Connection: close
""")
        writer.flush()

        output.write(htmlBytes)
        output.flush()
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
        if (debugEnabled) log.debug("Entering sendError(), code=$code, message='$message', details='$details'.")

        val messageString = "$code $message" + if (details.isEmpty()) "" else "\n$details"
        val messageStringLength = messageString.length + 1

        writer.println("""HTTP/1.1 $code $message
Content-Type: text/plain
Content-Length: $messageStringLength
Connection: close

$messageString""")
    }

    private fun createFileFromPost(input: CharArray): File {
        val decoded =
            URLDecoder.decode(String(input), StandardCharsets.UTF_8.name())

        val prefix = "$postDataFieldName="
        val inputAsString = if (decoded.startsWith(prefix)) {
            // Add 1 to substring start index to handle the "=" between the POST data
            // field name and the POST data field data.
            decoded.substring(prefix.length)
        } else{
            log.warn("Expecting a data field named " + postDataFieldName)
            ""
        }

        val file = File(playgroundFilePath)
        try {
            file.writeText(inputAsString)
        } catch (e: Exception) {
            log.error("Error creating playground file: {}", e.message)
            throw IOException("Failed to write playground source file", e)
        }
        return file
    }

    data class JavaExecutionResult(
        val compileOutput: String,
        val runOutput: String,
        val timedOut: Boolean,
        val compileTimeMs: Long,
        val timeoutLimit: Long // Added field
    )
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
        Files.deleteIfExists(Paths.get(sourceFile.path))

        return JavaExecutionResult(compileOutput, runOutput, !didJavaFinish, compileTime, timeoutSeconds)
    }

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
