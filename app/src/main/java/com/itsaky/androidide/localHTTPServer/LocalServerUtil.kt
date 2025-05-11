/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.localHTTPServer

import android.util.Log
import android.util.Log.d
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.idetooltips.DocumentationDatabase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import okhttp3.internal.connection.Exchange
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Exchanger
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.time.measureTime

class LocalServerUtil() {

    val TAG = this.javaClass.simpleName
    val DEFAULT_LANGUAGE = "en-us"
    val ACCEPT_LANGUAGES = "Accept-Language"
    val ACCEPT_ENCODINGS = "Accept-Encoding"
    val ACCEPT = "Accept"
    val BROTLI_TAG = "br"
    val BROTLI_COMPRESSION = "brotli"
    val BROTLI_COMMAND = "/data/data/com.itsaky.androidide/files/usr/bin/brotli"
    val BROTLI_TIMEOUT = 5 * 1000L



    var httpServer : HttpServer? = null
    var serverUp = false
    val context = IDEApplication.instance.applicationContext

    fun startServer(port: Int) {
        try {
//            httpServer = HttpServer.create(InetSocketAddress("localhost", port), 0)
            httpServer = HttpServer.create(InetSocketAddress(port), 0)
            httpServer!!.executor = Executors.newCachedThreadPool()
            httpServer!!.createContext("/", rootHandler)
            // 'this' refers to the handle method
            httpServer!!.createContext("/index", rootHandler)
            httpServer!!.start()//start server;
            d(TAG, "Server is running on ${httpServer!!.address}:$port")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun sendTextResponse(httpExchange: HttpExchange, responseText: String,
                         contentType: String){
        // Encode response text as UTF-8 and convert to ByteArray
        val respByteArray = responseText.encodeToByteArray()
        httpExchange.getResponseHeaders().put("Content-Type", listOf(contentType))
        httpExchange.sendResponseHeaders(200, respByteArray.size.toLong())

        val os = httpExchange.responseBody
        os.write(respByteArray)
        os.close()
    }

    fun sendErrorResponse(httpExchange: HttpExchange, responseText: String,
                         contentType: String){
        // Encode response text as UTF-8 and convert to ByteArray
        val respByteArray = responseText.encodeToByteArray()
        httpExchange.getResponseHeaders().put("Content-Type", listOf(contentType))
        httpExchange.sendResponseHeaders(404, respByteArray.size.toLong())

        val os = httpExchange.responseBody
        os.write(respByteArray)
        os.close()
    }

    fun sendBinaryResponse(
        httpExchange: HttpExchange, responseData: ByteArray?,
        contentType: String
    ) {
        httpExchange.getResponseHeaders().put("Content-Type", listOf(contentType))
        httpExchange.sendResponseHeaders(200, responseData?.size?.toLong() ?: 0)

        val os = httpExchange.responseBody
        os.write(responseData)
        os.close()
    }

    //shutdown the server
    fun stopServer() {
        if (httpServer != null){
            httpServer!!.stop(0)
        }
    }

    // Handler for root endpoint
    public val rootHandler = HttpHandler { exchange ->
        run {
            // Get request method
            Log.d(TAG, exchange.toString())
            when (exchange!!.requestMethod) {
                "GET" -> {
                    Log.d(TAG, exchange.requestHeaders.toString())
                    Log.d(TAG, exchange.requestURI.toString())


                    val querySplitPath = exchange.requestURI.toString().split("?")
                    var rawFilePath = querySplitPath[0].toString()
                    if (rawFilePath.startsWith("/")) {
                        rawFilePath = rawFilePath.substring(1)
                    }
                    // Get path components
                    val database = DocumentationDatabase.getDatabase(context)
                    val contentDao = database.contentDao()
                    val contentTypeDao = database.contentTypeDao()
                    val contentItem = contentDao.getContent(rawFilePath)

                    if (contentItem == null) {
                        sendErrorResponse(exchange, formatNotFoundError(rawFilePath), "text/html")
                    } else {
                        val typeItem =   //contentItem.contentTypeID
                            contentTypeDao.getContentTypeById(contentItem!!.contentTypeID)
                        val type = typeItem!!.value
                        // Extract file extension for content type header
                        val headers = exchange.requestHeaders
                        var acceptsBrotli = false
                        for (key in headers.keys) {
                            if (key.equals(ACCEPT_ENCODINGS)) {
                                val headerValues = headers.get(key)
                                if (headerValues!!.equals(BROTLI_TAG)) {
                                    acceptsBrotli = true
                                }
                            }
                        }

                        var html: ByteArray? = contentItem.content
                        if (!acceptsBrotli && typeItem!!.compression.equals("brotli")) {
                            val duration = measureTime {
                                html = decodeBrotli(
                                    BROTLI_COMMAND,
                                    contentItem.content,
                                    BROTLI_TIMEOUT
                                )
                            }
                            Log.d(TAG, "brotli took $duration")
                        }

//                        if (typeItem!!.compression.equals("None")) {
//                            sendTextResponse(exchange, html.toString(), type)
//                        } else {
                            sendBinaryResponse(exchange, html, type)
//                        }
                    }
                }
            }
        }
    }

    /**
     * Formats a 404 Not Found error message.  This function provides a
     * consistent and user-friendly way to display 404 errors in an Android
     * application.  It encapsulates the error message construction,
     * allowing for easy modification and localization.
     *
     * @param url The URL that was not found.
     * @return A formatted string representing the 404 error.
     */
    fun formatNotFoundError(url: String): String {
        return """
        <html>
        <head>
            <title>404 Not Found</title>
            <style>
                body {
                    font-family: 'Arial', sans-serif;
                    background-color: #f4f4f4;
                    color: #333;
                    margin: 0;
                    padding: 0;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    min-height: 100vh;
                    text-align: center;
                }
                .container {
                    max-width: 80%; /* Responsive width */
                    padding: 20px;
                    background-color: #fff;
                    border-radius: 8px;
                    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1); /* Subtle shadow */
                    border: 1px solid #ddd;
                }
                h1 {
                    font-size: 2.5em; /* Larger heading */
                    color: #4CAF50; /* A shade of green */
                    margin-bottom: 10px;
                }
                p {
                    font-size: 1.1em; /* Slightly larger paragraph text */
                    margin-bottom: 20px;
                    line-height: 1.5; /* Improved readability */
                }
                hr {
                    border: none;
                    height: 1px;
                    background-color: #ddd; /* Lighter divider */
                    margin: 20px 0;
                }
                a {
                    color: #0078d7; /* Standard blue for links */
                    text-decoration: none;
                }
                a:hover {
                    text-decoration: underline; /* Indicate link on hover */
                }
                .error-details {
                  background-color: #f8f8f8;
                  padding: 10px;
                  border-radius: 5px;
                  margin-top: 10px;
                  font-size: 0.9em;
                  color: #555;
                  border: 1px solid #eee;
                  overflow-wrap: break-word;
                  word-break: break-all;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>404 Not Found</h1>
                <p>Sorry, the page you are looking for could not be found.</p>
                <hr>
                <p>The requested URL 
                    <span class="error-details">
                        ${htmlEncode(url)}
                    </span>
                 was not found on this server.</p>
                <p>
                    <a href="/">Back to Home</a>
                </p>
            </div>
        </body>
        </html>
        """.trimIndent()

    }

    /**
     * Utility function to HTML encode a string.  This is essential to prevent
     * Cross-Site Scripting (XSS) vulnerabilities when displaying user-provided
     * data (like a URL) in an HTML page.  It replaces special characters
     * with their corresponding HTML entities.
     */
    private fun htmlEncode(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#039;")
    }

    /**
     * Sends binary data to the brotli application via a pipe and receives a binary
     * result. Handles errors robustly and logs information.  This version includes a timeout.
     *
     * @param command The command to execute the external application.
     * @param inputData The binary data to send to the external application.
     * @param timeoutSeconds The maximum time to wait for the application to respond.
     * @return The binary result received from the external application, or null if an
     * error occurred or the timeout was reached.
     */
    fun decodeBrotli(
        command: String,
        inputData: ByteArray,
        timeoutSeconds: Long = 10L
    ): ByteArray? {
        var process: Process? = null
        var inputStream: OutputStream? = null
        var outputStream: InputStream? = null
        var errorStream: InputStream? = null

        try {
            // 1. Execute the external application.
            val processBuilder = ProcessBuilder(command, "--decompress")
            process = processBuilder.start()

            // 2. Get the input and output streams of the process.
            process.outputStream.use { stdIn ->
                inputStream = stdIn
                // 3. Write the binary input data to the application's standard input.
                stdIn.write(inputData)
                stdIn.flush()
            }

            // 4. Wait for the process to complete with a timeout.
            val completed = if (timeoutSeconds > 0) {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } else {
                process.waitFor()
                true
            }

            if (!completed) {
                Log.e(TAG, "External application timed out after $timeoutSeconds seconds.")
                process.destroy()
                return null
            }

            // 5. Check the exit value.
            val exitValue = process.exitValue()
            if (exitValue != 0) {
                errorStream = process.errorStream
                val errorText = errorStream.bufferedReader().use { it.readText() }
                Log.e(TAG, "External application exited with error code $exitValue. Error output: $errorText")
                return null
            }

            // 6. Read the binary output from the application's standard output.
            process.inputStream.use { stdOut ->
                outputStream = stdOut
                val buffer = ByteArray(4096) // Use a buffer to read in chunks
                val output = ByteArrayOutputStream()
                var bytesRead: Int
                while (stdOut.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                val result = output.toByteArray()
                Log.d(TAG, "Received binary result of size: ${result.size} bytes")
                return result
            }

        } catch (e: IOException) {
            Log.e(TAG, "IOException: ${e.message}", e)
            return null
        } catch (e: InterruptedException) {
            Log.e(TAG, "InterruptedException: ${e.message}", e)
            return null
        } finally {
            // 7. Clean up resources.
            if (process != null) {
                process.destroy()
            }
        }
    }


}