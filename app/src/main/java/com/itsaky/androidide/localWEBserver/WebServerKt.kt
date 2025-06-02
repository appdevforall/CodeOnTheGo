package org.appdevforall.localwebserver

fun main() {
    val config = WebServer.loadConfig()
    val server = WebServer(config)
    server.start()
} 