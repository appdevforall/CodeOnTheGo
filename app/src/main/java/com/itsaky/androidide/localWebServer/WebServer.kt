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
import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale

data class ServerConfig(
	val port: Int = 6174,
	val databasePath: String,
	val bindName: String = "localhost",
	val debugDatabasePath: String =
		android.os.Environment
			.getExternalStorageDirectory()
			.toString() +
			"/Download/documentation.db",
)

class WebServer(
	private val config: ServerConfig,
) {
	private lateinit var serverSocket: ServerSocket
	private lateinit var database: SQLiteDatabase
	private var databaseTimestamp: Long = -1
	private var brotliSupported = false
	private val encodingHeader: String = "Accept-Encoding"
	private val brotliCompression: String = "br"

	companion object {
		private const val TAG = "WebServer"
	}

	// function to obtain the last modified date of a documentation.db database
	// this is used to see if there is a newer version of the database on the sdcard
	fun getDatabaseTimestamp(
		pathname: String,
		silent: Boolean = false,
	): Long {
		val dbFile = File(pathname)
		var timestamp: Long = -1

		if (dbFile.exists()) {
			timestamp = dbFile.lastModified()

			if (!silent) {
				val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

				Log.d(
					TAG,
					"$pathname was last modified at ${dateFormat.format(Date(timestamp))}.",
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
				database = SQLiteDatabase.openDatabase(config.databasePath, null, SQLiteDatabase.OPEN_READONLY)
			} catch (e: Exception) {
				Log.e(TAG, "Cannot open database: ${e.message}")
				return
			}

			serverSocket = ServerSocket(config.port, 0, java.net.InetAddress.getByName(config.bindName))
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
		val output = clientSocket.getOutputStream()
		val writer = PrintWriter(output, true)
		val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
		brotliSupported = false // assume nothing

		// Read the request method line, it is always the first line of the request
		var requestLine = reader.readLine() ?: return

		// Parse the request
		// Request line should look like "GET /a/b/c.html HTTP/1.1"
		val parts = requestLine.split(" ")
		if (parts.size != 3) {
			return sendError(writer, 400, "Bad Request")
		}

		// extract the request method (e.g. GET, POST, PUT)
		val method = parts[0]
		var path = parts[1].split("?")[0] // Discard any HTTP query parameters.
		path = path.substring(1)

		// we only support teh GET method, return an error page for anything else
		if (method != "GET") {
			return sendError(writer, 501, "Not Implemented")
		}

		// the HTTP headers follow the the method line, read until eof or 0 length
		// if we encounter the Encoding Header, check to see if brotli encoding (br) is supported
		while (requestLine.length > 0) {
			requestLine = reader.readLine() ?: break
			if (requestLine.startsWith(encodingHeader)) {
				val parts = requestLine.replace(" ", "").split(":")[1].split(",")
				if (parts.size == 0) {
					break
				}
				brotliSupported = parts.contains(brotliCompression)
				break
			}
		}

		// check to see if there is a newer version of the documentation.db database on the sdcard
		// if there is use that for our responses
		val debugDatabaseTimestamp = getDatabaseTimestamp(config.debugDatabasePath, true)
		if (debugDatabaseTimestamp > databaseTimestamp) {
			database.close()
			database = SQLiteDatabase.openDatabase(config.debugDatabasePath, null, SQLiteDatabase.OPEN_READONLY)
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

		// if there is not an entry, or more than 1 entry, in the database, return an error
		// if rowCount is zero then there was no entry in the database
		// otherwise if the entry path was not unique, consider this an invalid path
		if (rowCount != 1) {
			return if (rowCount == 0) {
				sendError(writer, 404, "Not Found")
			} else {
				sendError(writer, 406, "Not Acceptable")
			}
		}

		cursor.moveToFirst()
		var dbContent = cursor.getBlob(0)
		val dbMimeType = cursor.getString(1)
		var compression = cursor.getString(2)

		// If the Accept-Encoding header contains "br", the client can handle
		// Brotli. Send Brotli data as-is, without decompressing it here.
		// If the client can't handle Brotli, and the content is Brotli-
		// compressed, decompress the content here.

		if (compression == "brotli") {
			if (brotliSupported) {
				compression = "br"
			} else {
				try {
					dbContent =
						BrotliInputStream(ByteArrayInputStream(dbContent)).use { it.readBytes() }
					compression = "none"
				} catch (e: Exception) {
					Log.e(TAG, "Error decompressing Brotli content: ${e.message}")
					return sendError(writer, 500, "Internal Server Error")
				}
			}
		}

		// send our response
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
		cursor.close()
	}

	private fun sendError(
		writer: PrintWriter,
		code: Int,
		message: String,
	) {
		writer.println("HTTP/1.1 $code $message")
		writer.println("Content-Type: text/plain")
		writer.println("Connection: close")
		writer.println()
		writer.println("$code $message")
	}
}
