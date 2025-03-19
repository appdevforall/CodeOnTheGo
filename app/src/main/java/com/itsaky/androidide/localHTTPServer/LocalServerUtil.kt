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

import android.content.Context
import android.util.Log
import android.util.Log.*
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.utils.FileUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.Scanner
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors

class LocalServerUtil() {

    val TAG = this.javaClass.simpleName
    var httpServer : HttpServer? = null
    var serverUp = false
    val context = IDEApplication.instance.applicationContext

    fun startServer(port: Int) {
        try {
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

    fun streamToString(inputStream: InputStream): String {
        val s = Scanner(inputStream).useDelimiter("\\A")
        return if (s.hasNext()) s.next() else ""
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

    fun sendBinaryResponse(httpExchange: HttpExchange, responseData: ByteArray,
                           contentType: String) {
        httpExchange.getResponseHeaders().put("Content-Type", listOf(contentType))
        httpExchange.sendResponseHeaders(200, responseData.size.toLong())

        val os = httpExchange.responseBody
        os.write(responseData)
        os.close()
    }

    fun stopServer() {
        if (httpServer != null){
            httpServer!!.stop(0)
//            serverTextView.text = getString(R.string.server_down)
//            serverButton.text = getString(R.string.start_server)
        }
    }

    // Handler for root endpoint
    public val rootHandler = HttpHandler { exchange ->
        run {
            // Get request method
            when (exchange!!.requestMethod) {
                "GET" -> {
                    Log.d("HTTPRequest", exchange.requestURI.toString())

                    // Chop off query params so they're not included in the file URI
                    // that we actually read from. Content is static so these
                    // parameters only matter for client-side behavior.
                    val querySplitPath = exchange.requestURI.toString().split("?")
                    val rawFilePath = querySplitPath[0]

                    // Get path components
                    val splitPath = rawFilePath.split("/")

                    // Extract file extension for content type header
                    val basename = splitPath[splitPath.size - 1]
                    val dotSplitBasename = basename.split(".")
                    val extension = dotSplitBasename[dotSplitBasename.size - 1]

                    // Get actual file URI with respect to document root
                    val slicedSplitPath = splitPath.slice(1..splitPath.size - 1)
                    val fileURI = slicedSplitPath.joinToString(separator = "/")

                    // Process text and binary data requests separately
                    var isText = false
                    var contentType = ""

                    // Extension to content type maps should be stored somewhere
                    // properly. Refactor this.
                    if (extension == "css") {
                        contentType = "text/css"
                        isText = true
                    } else if (extension == "png") {
                        contentType = "image/png"
                    } else if (extension == "gif") {
                        contentType = "image/gif"
                    } else if (extension == "js") {
                        contentType = "text/javascript"
                        isText = true
                    } else {
                        contentType = "text/html; charset=utf-8"
                        isText = true
                    }

                    // Process text and binary documents separately
                    if (isText) {
                        val responseContent = readFromAsset("CoGoTooltips/html/" + fileURI, context)
                        sendTextResponse(exchange, responseContent, contentType)
                    } else {
                        val responseContent = readFromBinaryAsset("CoGoTooltips/html/" + fileURI, context)
                        sendBinaryResponse(exchange, responseContent, contentType)
                    }
                }
            }
        }
    }

    val messageHandler = HttpHandler { httpExchange ->
        run {
            when (httpExchange!!.requestMethod) {
                "GET" -> {
                    // Get all messages
                    sendTextResponse(httpExchange, "Would be all messages stringified json", "text/html; charset=utf-8")
                }
                "POST" -> {
                    val inputStream = httpExchange.requestBody

                    val requestBody = streamToString(inputStream)
                    val jsonBody = JSONObject(requestBody)
                    // save message to database

                    //for testing
                    sendTextResponse(httpExchange, jsonBody.toString(), "text/html; charset=utf-8")
                }
            }
        }
    }

    /**
     * Reads from an asset file and returns its content as a String.
     *
     * @param path The path to the asset file
     * @param ctx The context from which the asset should be read
     * @return The content of the asset file as a String
     */
    fun readFromAsset(path: String?, ctx: Context): String {
        try {
            // Get the input stream from the asset
            val inputStream = ctx.assets.open(path!!)

            // Create a byte array output stream to store the read bytes
            val outputStream = ByteArrayOutputStream()

            // Create a buffer of 1024 bytes
            val _buf = ByteArray(1024)
            var i: Int

            // Read the bytes from the input stream, write them to the output stream and close the streams
            while ((inputStream.read(_buf).also { i = it }) != -1) {
                outputStream.write(_buf, 0, i)
            }
            outputStream.close()
            inputStream.close()

            // Return the content of the output stream as a String
            return outputStream.toString()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If an exception occurred, return an empty String
        return ""
    }

    /**
     * Reads from an asset file and returns its content as a ByteArray.
     *
     * @param path The path to the asset file
     * @param ctx The context from which the asset should be read
     * @return The content of the asset file as a String
     */
    fun readFromBinaryAsset(path: String?, ctx: Context): ByteArray {
        try {
            // Get the input stream from the asset
            val inputStream = ctx.assets.open(path!!)

            // Create a byte array output stream to store the read bytes
            val outputStream = ByteArrayOutputStream()

            // Create a buffer of 1024 bytes
            val _buf = ByteArray(1024)
            var i: Int

            // Read the bytes from the input stream, write them to the output stream and close the streams
            while ((inputStream.read(_buf).also { i = it }) != -1) {
                outputStream.write(_buf, 0, i)
            }
            outputStream.close()
            inputStream.close()

            // Return the content of the output stream as a String
            return outputStream.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If an exception occurred, return an empty ByteArray
        return byteArrayOf()
    }
}