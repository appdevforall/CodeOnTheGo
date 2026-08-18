/*
 *  This file is part of Code on the Go.
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

package com.itsaky.androidide.documentation

import android.database.sqlite.SQLiteDatabase
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Answers documentation requests from `documentation.db` in-process, so a WebView never opens a
 * socket to [com.itsaky.androidide.localWebServer.WebServer] for them (ADFA-5176).
 *
 * Wire it into a WebView through `WebViewClient.shouldInterceptRequest`. The URLs do not change:
 * this matches the same `http://localhost:6174/...` space the server listens on, which is what
 * lets the strings.xml entries, ToolTipManager's link builder, and the plugin API contract stay
 * as they are. Anything this returns null for -- a templated page, a `/pr/` developer endpoint,
 * an unknown path, a read that fails -- falls through to that server unchanged.
 *
 * ADFA-5176 spike scope: templated pages (`Content.templateId > 0`) are deliberately not handled
 * here, because Pebble rendering still lives in WebServer. Moving that pipeline into one source
 * both transports call is the first step of the full change.
 */
class DocumentationRequestInterceptor(
	private val databaseFile: File,
) {
	private val log = LoggerFactory.getLogger(DocumentationRequestInterceptor::class.java)

	// Measurement switch for the ADFA-5176 spike: creating the sentinel forces documentation back
	// onto the local web server, so one build can measure both transports. Read once, like the
	// server's other file flags -- reopen the page after changing it.
	private val disabled =
		File(android.os.Environment.getExternalStorageDirectory(), DISABLE_SENTINEL).exists()

	private val servedRequests = AtomicLong()
	private val servedBytes = AtomicLong()

	private val database: SQLiteDatabase? by lazy {
		try {
			Brotli4jLoader.ensureAvailability()
			SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
		} catch (e: Exception) {
			log.warn("Cannot open '{}' for in-process documentation: {}", databaseFile, e.message)
			null
		}
	}

	/**
	 * The response for [request], or null to let it go to the network. Called on WebView's own
	 * threads, so it must stay thread-safe: [SQLiteDatabase] serializes its own reads and nothing
	 * here keeps mutable state.
	 */
	fun intercept(request: WebResourceRequest): WebResourceResponse? {
		if (disabled) return null
		if (!request.method.equals("GET", ignoreCase = true)) return null

		val url = request.url
		if (url.host != SERVER_HOST || url.port != SERVER_PORT) return null

		val path = url.path?.removePrefix("/").orEmpty()
		if (path.isEmpty() || path.startsWith("pr/")) return null

		return content(path)
	}

	private fun content(path: String): WebResourceResponse? {
		val database = this.database ?: return null

		return try {
			database.rawQuery(CONTENT_QUERY, arrayOf(path)).use { cursor ->
				if (cursor.count != 1 || !cursor.moveToFirst()) return null
				if (cursor.getInt(3) > 0) return null

				var bytes = cursor.getBlob(0)
				val mimeType = cursor.getString(1)
				val compression = cursor.getString(2)

				if (bytes.size == CONTENT_CHUNK_SIZE) bytes = reassemble(database, path, bytes)
				if (compression == "brotli") {
					// A WebView does not decode an intercepted response, so hand back plain bytes.
					bytes = BrotliInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
				}

				servedRequests.incrementAndGet()
				servedBytes.addAndGet(bytes.size.toLong())
				if (log.isDebugEnabled) log.debug("Served '{}' in-process, {} bytes.", path, bytes.size)

				response(mimeType, bytes)
			}
		} catch (e: Exception) {
			log.warn("Cannot serve '{}' in-process, falling back to the web server: {}", path, e.message)
			null
		}
	}

	/**
	 * Content over [CONTENT_CHUNK_SIZE] is stored across rows named `path-1`, `path-2`, ... Matches
	 * the numbering WebServer uses, including its known off-by-one on some rows (ADFA-5170).
	 */
	private fun reassemble(
		database: SQLiteDatabase,
		path: String,
		firstChunk: ByteArray,
	): ByteArray {
		val combined = ByteArrayOutputStream().apply { write(firstChunk) }
		var chunk = firstChunk
		var chunkNumber = 1

		while (chunk.size == CONTENT_CHUNK_SIZE) {
			database.rawQuery(CHUNK_QUERY, arrayOf("$path-$chunkNumber")).use { cursor ->
				if (!cursor.moveToFirst()) return combined.toByteArray()
				chunk = cursor.getBlob(0)
				combined.write(chunk)
				chunkNumber++
			}
		}

		return combined.toByteArray()
	}

	private fun response(
		mimeType: String,
		bytes: ByteArray,
	): WebResourceResponse {
		// WebResourceResponse wants the bare type; a charset travels in its own parameter.
		val type = mimeType.substringBefore(';').trim()
		val charset =
			mimeType
				.substringAfter("charset=", "")
				.substringBefore(';')
				.trim()
				.ifEmpty { if (type.startsWith("text/")) "utf-8" else null }

		return WebResourceResponse(type, charset, ByteArrayInputStream(bytes))
	}

	/** What this instance has answered without a socket, for the ADFA-5176 measurement. */
	fun servedSummary(): String =
		if (disabled) {
			"in-process serving is off (${DISABLE_SENTINEL} exists)"
		} else {
			"${servedRequests.get()} requests, ${servedBytes.get()} bytes served in-process"
		}

	private companion object {
		const val DISABLE_SENTINEL = "Download/CodeOnTheGo.nointercept"
		const val SERVER_HOST = "localhost"
		const val SERVER_PORT = 6174
		const val CONTENT_CHUNK_SIZE = 1024 * 1024

		const val CONTENT_QUERY = """
			SELECT C.content, CT.value, CT.compression, C.templateId
			FROM   Content C, ContentTypes CT
			WHERE  C.contentTypeID = CT.id
			AND    C.path = ?
		"""

		const val CHUNK_QUERY = "SELECT content FROM Content WHERE path = ? AND languageId = 1"
	}
}
