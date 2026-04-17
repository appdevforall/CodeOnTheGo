package org.appdevforall.localwebserver

import android.database.sqlite.SQLiteDatabase
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
data class ServerConfig(
    val port: Int = 6174,
    val databasePath: String,
    val fileDirPath: String,
    val bindName: String = "localhost",
    val debugDatabasePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/documentation.db",
    val debugEnablePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/CodeOnTheGo.webserver.debug",
    val experimentsEnablePath: String = android.os.Environment.getExternalStorageDirectory().toString() +
            "/Download/CodeOnTheGo.exp", // TODO: Centralize this concept. --DS, 9-Feb-2026

// Yes, this is hack code.
    val projectDatabasePath: String = "/data/data/com.itsaky.androidide/databases/RecentProject_database"
)

data class JavaExecutionResult(
    val compileOutput: String,
    val runOutput: String,
    val timedOut: Boolean,
    val compileTimeMs: Long,
    val timeoutLimit: Long
)

class WebServer(private val config: ServerConfig) {
    private lateinit var serverSocket       : ServerSocket
    private lateinit var database           : SQLiteDatabase
    private          var databaseTimestamp  : Long    = -1
    private          val log                          = LoggerFactory.getLogger(WebServer::class.java)
    private          val debugEnabled       : Boolean = File(config.debugEnablePath).exists()
    // TODO: Use the centralized experiments flag instead of this ad-hoc check. --DS, 10-Feb-2026
    private          val experimentsEnabled : Boolean = File(config.experimentsEnablePath).exists() // Frozen at startup. Restart server if needed.
    private          val encodingHeader     : String  = "Accept-Encoding"
    private          val brotliCompression  : String  = "br"


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
            log.info(
                "Starting WebServer on {}, port {}, debugEnabled={}, debugEnablePath='{}', debugDatabasePath='{}', experimentsEnabled={}, experimentsEnablePath='{}'.",
                config.bindName,
                config.port,
                debugEnabled,
                config.debugEnablePath,
                config.debugDatabasePath,
                experimentsEnabled,
                config.experimentsEnablePath
            )

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
            log.info("WebServer started successfully on '{}', port {}.", config.bindName, config.port)

            while (true) {
                var clientSocket: Socket? = null
                try {
                    try {
                        if (debugEnabled) log.debug("About to call accept() on the server socket, {}.", serverSocket)
                        clientSocket = serverSocket.accept()

                        if (debugEnabled) log.debug("Returned from socket accept(), clientSocket is {}.", clientSocket)

                    } catch (e: java.net.SocketException) {
                        if (debugEnabled) log.debug("Caught java.net.SocketException '$e'.") // SLF4J placeholders produce wrong formatting here. --DS, 23-Feb-2026

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
                        if (debugEnabled) log.debug("Caught exception '$e'.")  // SLF4J placeholders produce wrong formatting here. --DS, 23-Feb-2026

                        if (e is java.net.SocketException && e.message?.contains("Closed", ignoreCase = true) == true) {
                            if (debugEnabled) log.debug("Client disconnected: {}", e.message)

                        } else {
                            log.error("Error handling client: {}", e.message)
                            clientSocket?.let { socket ->
                                try {
                                    val output = socket.outputStream

/*
The code below handles rare errors sufficiently correctly. To make it "more correct" will complicate the code,
adding more chances for bugs. Therefore I'm ignoring the CodeRabbit message below. --DS, 23-Feb-2026

Avoid sending a 500 after a partial response.

If handleClient already wrote headers/body before throwing, this fallback sendError will append another response and garble the stream. Gate the error send on a response-started flag from handleClient (or just log/close when unsure).
🤖 Prompt for AI Agents

Verify each finding against the current code and only fix it if needed.

In `@app/src/main/java/com/itsaky/androidide/localWebServer/WebServer.kt` around
lines 151 - 163, The fallback in the catch block unconditionally calls sendError
which can corrupt the stream if handleClient already started sending a response;
modify handleClient and the surrounding logic to track whether a response has
started (e.g., add a responseStarted boolean returned by or set by handleClient,
or expose a getResponseStarted() on the request handler) and only call sendError
when responseStarted is false; otherwise avoid writing another response and
instead log the error and close clientSocket (use clientSocket?.close()) to
cleanly terminate the connection. Ensure references to handleClient, sendError,
clientSocket and the catch block logic are updated accordingly.


 */
                                    sendError(PrintWriter(output, true), output, 500, "Internal Server Error 1")

                                } catch (e2: Exception) {
                                    log.error("Error sending error response: {}", e2.message)
                                }
                            }
                        }
                    }

                } finally {
                    clientSocket?.close()

                    // CodeRabbit objects to the following line because clientSocket may print out as "null." This is intentional. --DS
                    if (debugEnabled) log.debug("clientSocket was {}.", clientSocket)
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

    /**
     * Reads a single line from the stream (bytes until newline). Same stream is used for headers
     * and body so POST body bytes are not lost to a separate buffered reader. HTTP header lines are ASCII.
     */
    private fun readLineFromStream(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (baos.size() == 0) null else baos.toString(Charsets.ISO_8859_1).trimEnd('\r')
            if (b == '\n'.code) break
            baos.write(b)
        }
        val bytes = baos.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, Charsets.ISO_8859_1)
    }

    private fun handleClient(clientSocket: Socket) {
        if (debugEnabled) log.debug("In handleClient(), socket is {}.", clientSocket)

        val input = clientSocket.getInputStream()
        if (debugEnabled) log.debug("  input is {}.", input)

        val output = clientSocket.getOutputStream()
        if (debugEnabled) log.debug("  output is {}.", output)

        val writer = PrintWriter(output, true)
        if (debugEnabled) log.debug("  writer is {}.", writer)

        var brotliSupported = false //assume nothing

        // Read the request method line, it is always the first line of the request
        var requestLine = readLineFromStream(input)
        if (requestLine == null) {
            if (debugEnabled) log.debug("requestLine is null. Returning from handleClient() early.")
            return
        }
        if (debugEnabled) log.debug("Request is {}", requestLine)

        // Parse the request
        // Request line should look like "GET /a/b/c.html HTTP/1.1"
        val parts = requestLine.split(" ")
        if (parts.size != 3) {
            return sendError(writer, output, 400, "Bad Request")
        }

        //extract the request method (e.g. GET, POST, PUT)
        val method = parts[0]
        var path   = parts[1].split("?")[0] // Discard any HTTP query parameters.
        path = path.substring(1)

        // Read all headers until blank line (needed for Content-Length on POST and Accept-Encoding on GET)
        val headers = mutableMapOf<String, String>()
        while (true) {
            requestLine = readLineFromStream(input) ?: break
            if (requestLine.isEmpty()) break
            if (debugEnabled) log.debug("Header: {}", requestLine)
            val colon = requestLine.indexOf(':')
            if (colon > 0) {
                headers[requestLine.substring(0, colon).trim().lowercase()] = requestLine.substring(colon + 1).trim()
            }
        }
        brotliSupported = headers["accept-encoding"]?.contains(brotliCompression) == true

        // Playground endpoint: POST only, handled before GET-only check
        if (false && path == "playground/execute") {
            return handlePlaygroundExecute(input, writer, output, method, headers)
        }

        // we only support teh GET method, return an error page for anything else
        if (method != "GET") {
            return sendError(writer, output, 501, "Not Implemented")
        }

        //check to see if there is a newer version of the documentation.db database on the sdcard
        // if there is use that for our responses
        val debugDatabaseTimestamp = getDatabaseTimestamp(config.debugDatabasePath, true)
        if (debugDatabaseTimestamp > databaseTimestamp) {
            database.close()
            database = SQLiteDatabase.openDatabase(config.debugDatabasePath, null, SQLiteDatabase.OPEN_READONLY)
            databaseTimestamp = debugDatabaseTimestamp
        }

        // Handle the special "pr" endpoint with highest priority
        if (path.startsWith("pr/", false)) {
            if (debugEnabled) log.debug("Found a pr/ path, '{}'.", path)

            return when (path) {
                "pr/db" -> handleDbEndpoint(writer, output)
                "pr/pr" -> handlePrEndpoint(writer, output)
                "pr/ex" -> handleExEndpoint(writer, output)
                else    -> sendError(writer, output, 404, "Not Found", "Path requested: '$path'.")
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

        if (debugEnabled) log.debug("Database fetch for path='{}' returned {} rows.", path, rowCount)

        var dbContent   : ByteArray
        var dbMimeType  : String
        var compression : String

        try {
            if (rowCount != 1) {
                return when (rowCount) {
                    0 -> sendError(writer, output, 404, "Not Found", "Path requested: '$path'.")
                    else -> sendError(
                        writer,
                        output,
                        500,
                        "Internal Server Error 2",
                        "Corrupt database - multiple records found when unique record expected, Path requested: '$path'."
                    )
                }
            }

            cursor.moveToFirst()
            dbContent   = cursor.getBlob(0)
            dbMimeType  = cursor.getString(1)
            compression = cursor.getString(2)

            if (debugEnabled) log.debug("len(content)={}, MIME type={}, compression={}.", dbContent.size, dbMimeType, compression)

        } finally {
            cursor.close()
        }

        if (dbContent.size == 1024 * 1024) { // Could use fragmentation to satisfy range requests but only for uncompressed content.
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
                if (debugEnabled) log.debug("DB item > 1 MB. fragment#{} path2='{}'.", fragmentNumber, path2)

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
                    dbContent = BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
                    compression = "none"

                } catch (e: Exception) {
                    log.error("Error decompressing Brotli content: {}", e.message)
                    return sendError(writer, output, 500, "Internal Server Error 3")
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

        var html : String

        try {
            // First, get the schema of the LastChange table to determine column count
            val schemaQuery = "PRAGMA table_info(LastChange)"
            val schemaCursor = database.rawQuery(schemaQuery, arrayOf())

            var columnCount: Int
            var selectColumns: String

            html = getTableHtml("LastChange Table", "LastChange Table (20 Most Recent Rows)")

            try {
                columnCount = schemaCursor.count
                val columnNames = mutableListOf<String>()

                while (schemaCursor.moveToNext()) {
                    // Values come from schema introspection, therefore not subject to a SQL injection attack.
                    columnNames.add(schemaCursor.getString(1)) // Column name is at index 1
                }

                if (debugEnabled) log.debug(
                    "LastChange table has {} columns: {}",
                    columnCount,
                    columnNames
                )

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

            val dataQuery =
                "SELECT $selectColumns FROM LastChange ORDER BY changeTime DESC LIMIT 20"

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

            if (debugEnabled) log.debug("html is '{}'.", html)
        } catch (e: Exception) {
            log.error("Error creating output for /pr/db endpoint: {}", e.message)
            sendError(
                writer,
                output,
                500,
                "Internal Server Error 4.1",
                "Error creating output."
            )
            return
        }

        try {
            writeNormalToClient(writer, output, html)

            if (debugEnabled) log.debug("Leaving handleDbEndpoint().")

        } catch (e: Exception) {
            log.error("Error handling /pr/db endpoint: {}", e.message)
            sendError(writer, output, 500, "Internal Server Error 4", "Error generating database table.", true)
        }
    }

    private fun handleExEndpoint(writer: PrintWriter, output: java.io.OutputStream) {
        val flag = if (experimentsEnabled)  "{}" else "{display: none;}"

        if (debugEnabled) log.debug("Experiment flag='{}'.", flag)

        sendCSS(writer, output, ".code_on_the_go_experiment $flag")
    }

    private fun handlePrEndpoint(writer: PrintWriter, output: java.io.OutputStream) {
        if (debugEnabled) log.debug("Entering handlePrEndpoint().")

        var projectDatabase : SQLiteDatabase? = null
        var outputStarted = false

        try {
            projectDatabase = SQLiteDatabase.openDatabase(config.projectDatabasePath,
                                                          null,
                                                          SQLiteDatabase.OPEN_READONLY)

 /* I disagree with CodeRabbit's message, reproduced below. However, the
    IDE's "Problems" window says that outputStarted is "always false."

     While writeNormalToClient() can fail in the middle of execution,
     making the error reporting code more complicated is likely to
     introduce more bugs, rather than helping fix existing ones. --DS, 23-Feb-2026

            482-494: ⚠️ Potential issue | 🟡 Minor

outputStarted is set too late to protect error handling.

If writeNormalToClient throws after headers are written, outputStarted remains false and the catch path will send a second response. Set/propagate this flag before the first write (e.g., via a mutable flag passed into realHandlePrEndpoint or by setting it just before writeNormalToClient and preserving it on exceptions).

Also applies to: 502-557

🤖 Prompt for AI Agents

Verify each finding against the current code and only fix it if needed.

In `@app/src/main/java/com/itsaky/androidide/localWebServer/WebServer.kt` around
lines 482 - 494, The catch block can send a second response because
outputStarted is only set after realHandlePrEndpoint returns; ensure the
"response started" flag is set before any write occurs by changing
realHandlePrEndpoint to accept and update a mutable flag (e.g., pass a
BooleanWrapper/MutableBoolean or an AtomicBoolean named outputStarted into
realHandlePrEndpoint) or by setting outputStarted immediately before the first
call to writeNormalToClient inside realHandlePrEndpoint; then have
realHandlePrEndpoint update that flag as soon as headers/body begin to be
written so sendError(writer, ...) checks the accurate flag and avoids sending a
second response.
             */
            outputStarted = realHandlePrEndpoint(writer, output, projectDatabase)

        } catch (e: Exception) {
            log.error("Error handling /pr/pr endpoint: {}", e.message)
            sendError(writer, output, 500, "Internal Server Error 6", "Error generating database table.", outputStarted)
            
        } finally {
            projectDatabase?.close()
        }

        if (debugEnabled) log.debug("Leaving handlePrEndpoint().")
    }

    private fun realHandlePrEndpoint(writer: PrintWriter, output: java.io.OutputStream, projectDatabase: SQLiteDatabase) : Boolean {
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

        if (debugEnabled) log.debug("html is '{}'.", html) // May output a lot of stuff but better too much than too little. --DS, 23-Feb-2026

        writeNormalToClient(writer, output, html)

        if (debugEnabled) log.debug("Leaving realHandlePrEndpoint().")

        return true
    }

    /**
     * Get HTML for table response page.
     */
    private fun getTableHtml(title: String, tableName: String): String {
        if (debugEnabled) log.debug("Entering getTableHtml(), title='{}', tableName='{}'.", title, tableName)

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
        if (debugEnabled) log.debug("Entering writeNormalToClient(), html='{}'.", html.take(200))

        val htmlBytes = html.toByteArray(Charsets.UTF_8)

        /*
        println() is intentional: the triple-quoted string ends with a single '\n' (after "Connection: close"),
        and println() appends the second '\n' to form the required blank-line HTTP header terminator ("\n\n"). --DS, 22-Feb-2026
        */
        writer.println("""HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Content-Length: ${htmlBytes.size}
Connection: close
""")

        output.write(htmlBytes)
        output.flush()
    }

    /**
     * Escapes HTML special characters to prevent XSS attacks.
     * Converts <, >, &, ", and ' to their HTML entity equivalents.
     */
    private fun escapeHtml(text: String): String {
//        if (debugEnabled) log.debug("Entering escapeHtml(), text='{}'.", text)

        return text
            .replace("&", "&amp;")   // Must be first to avoid double-escaping
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    private fun sendError(writer: PrintWriter, output: java.io.OutputStream, code: Int, message: String, details: String = "", outputStarted: Boolean = false) {
        if (debugEnabled) log.debug("Entering sendError(), code={}, message='{}', details='{}', outputStarted={}.", code, message, details, outputStarted)

        val messageString = "$code $message" + if (details.isEmpty()) "" else "\n$details"
        val bodyBytes = messageString.toByteArray(Charsets.UTF_8)

        if (!outputStarted) {
            writer.println(
                """HTTP/1.1 $code $message
Content-Type: text/plain; charset=utf-8
Content-Length: ${bodyBytes.size}
Connection: close
"""
            )
            output.write(bodyBytes)
            output.flush()
        }
        if (debugEnabled) log.debug("Leaving sendError().")
    }

    private fun sendCSS(writer: PrintWriter, output: java.io.OutputStream, message: String) {
        if (debugEnabled) log.debug("Entering sendCSS(), message='{}'.", message)

        val bodyBytes = message.toByteArray(Charsets.UTF_8)

        writer.println("""HTTP/1.1 200 OK
Content-Type: text/css; charset=utf-8
Content-Length: ${bodyBytes.size}
Cache-Control: no-store
Connection: close
""")

        output.write(bodyBytes)
        output.flush()

        if (debugEnabled) log.debug("Leaving sendCSS().")
    }

    private fun handlePlaygroundExecute(
        input: java.io.InputStream,
        writer: PrintWriter,
        output: java.io.OutputStream,
        method: String,
        headers: Map<String, String>
    ) {
        if (method != "POST") {
            return sendError(writer, output, 405, "Method Not Allowed")
        }
        val contentLengthStr = headers["content-length"] ?: run {
            return sendError(writer, output, 400, "Bad Request", "Missing Content-Length")
        }
        val contentLength = contentLengthStr.toIntOrNull() ?: run {
            return sendError(writer, output, 400, "Bad Request", "Invalid Content-Length")
        }
        if (contentLength <= 0) {
            return sendError(writer, output, 400, "Bad Request", "Content-Length must be positive")
        }
        if (contentLength > 10_000) {
            return sendError(writer, output, 413, "Payload Too Large")
        }
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            if (read <= 0) {
                return sendError(writer, output, 400, "Bad Request", "Input stream interrupted prematurely")
            }
            offset += read
        }
        val data = parseFormDataField(body, "data") ?: run {
            return sendError(writer, output, 400, "Bad Request", "Missing or empty form field 'data'")
        }
        if (data.size > 10_000) {
            return sendError(writer, output, 413, "Payload Too Large")
        }
        val workDir =
            File(config.fileDirPath, "playground_${System.nanoTime()}_${java.util.UUID.randomUUID()}")
                .apply { mkdirs() }
        try {
            val sourceFile = createFileFromPost(data, workDir)
            val result = compileAndRunJava(sourceFile)
            val sourceString = data.toString(Charsets.UTF_8)
            val responseBody = sourceString + result
            val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/plain; charset=utf-8")
            writer.println("Content-Length: ${responseBytes.size}")
            writer.println()
            writer.flush()
            output.write(responseBytes)
            output.flush()
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun parseFormDataField(body: ByteArray, fieldName: String): ByteArray? {
        val bodyStr = body.toString(Charsets.UTF_8)
        val pairs = bodyStr.split("&")
        for (pair in pairs) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
            if (key != fieldName) continue
            val value = pair.substring(eq + 1)
            val decoded = URLDecoder.decode(value, "UTF-8")
            if (decoded.isEmpty()) return null
            return decoded.toByteArray(Charsets.UTF_8)
        }
        return null
    }

    private fun createFileFromPost(data: ByteArray, workDir: File): File {
        require(data.size <= 10_000) { "data exceeds 10000 bytes" }
        val file = File(workDir, "Playground.java")
        file.writeBytes(data)
        return file
    }

    private fun compileAndRunJava(sourceFile: File): String {
        val dir = sourceFile.parentFile
        val fileName = sourceFile.nameWithoutExtension
        val classFile = File(dir, "$fileName.class")
        classFile.delete()
        val directoryPath = config.fileDirPath
        val javacPath = "$directoryPath/usr/bin/javac"
        val javaPath = "$directoryPath/usr/bin/java"
        val filePath = sourceFile.absolutePath

        val compileTimeoutSec = 60L
        val runTimeoutSec = 120L
        val destroyWaitSec = 5L

        try {
            val javac = ProcessBuilder(javacPath, filePath)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            javac.outputStream.close()
            val compileOutputRef = AtomicReference<String>("")
            val compileReader =
                Thread {
                    compileOutputRef.set(
                        javac.inputStream.bufferedReader().readText()
                    )
                }
            compileReader.start()
            val compileDone =
                javac.waitFor(compileTimeoutSec, TimeUnit.SECONDS)
            if (!compileDone) {
                javac.destroyForcibly()
                javac.waitFor(destroyWaitSec, TimeUnit.SECONDS)
                compileReader.join(1000)
                return "Compilation timed out after ${compileTimeoutSec}s:\n${compileOutputRef.get()}"
            }
            compileReader.join(2000)
            val compileOutput = compileOutputRef.get()
            if (javac.exitValue() != 0) {
                return "Compilation failed:\n$compileOutput"
            }

            val java =
                ProcessBuilder(
                    javaPath,
                    "-cp",
                    dir?.absolutePath ?: "",
                    fileName
                )
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            java.outputStream.close()
            val runOutputRef = AtomicReference<String>("")
            val runReader =
                Thread {
                    runOutputRef.set(
                        java.inputStream.bufferedReader().readText()
                    )
                }
            runReader.start()
            val runDone = java.waitFor(runTimeoutSec, TimeUnit.SECONDS)
            if (!runDone) {
                java.destroyForcibly()
                java.waitFor(destroyWaitSec, TimeUnit.SECONDS)
                runReader.join(1000)
                return "Execution timed out after ${runTimeoutSec}s:\n${runOutputRef.get()}"
            }
            runReader.join(2000)
            val runOutput = runOutputRef.get()

            return if (compileOutput.isNotBlank()) {
                "Compile output\n $compileOutput\n Program output\n$runOutput"
            } else {
                "Program output\n $runOutput"
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return "Compilation or execution interrupted."
        }
    }
}