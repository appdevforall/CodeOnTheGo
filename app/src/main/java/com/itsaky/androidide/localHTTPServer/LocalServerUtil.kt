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

import android.util.Log.*
import com.itsaky.androidide.app.IDEApplication
import com.itsvks.layouteditor.utils.FileUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.Scanner
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

     fun sendResponse(httpExchange: HttpExchange, responseText: String){
        httpExchange.sendResponseHeaders(200, responseText.length.toLong())
        val os = httpExchange.responseBody
        os.write(responseText.toByteArray())
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
                    ////Log.d("HTTPRequest", exchange.requestURI.toString())
                    val responseText = FileUtil.readFromAsset("CoGoTooltips/html/help_top.html", context)
                    sendResponse(exchange, responseText)
                }
            }
        }
    }

     val messageHandler = HttpHandler { httpExchange ->
        run {
            when (httpExchange!!.requestMethod) {
                "GET" -> {
                    // Get all messages
                    sendResponse(httpExchange, "Would be all messages stringified json")
                }
                "POST" -> {
                    val inputStream = httpExchange.requestBody

                    val requestBody = streamToString(inputStream)
                    val jsonBody = JSONObject(requestBody)
                    // save message to database

                    //for testing
                    sendResponse(httpExchange, jsonBody.toString())
                }
            }
        }
    }
}
