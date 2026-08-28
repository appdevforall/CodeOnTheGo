package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder
import com.aayushatharva.brotli4j.encoder.PreparedDictionary
import com.aayushatharva.brotli4j.encoder.PreparedDictionaryGenerator
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

private const val TAG = "DocumentationCompression"

/**
 * Copies [bytes] into a direct [ByteBuffer] -- brotli4j's `attachDictionary` requires a direct
 * buffer, a heap-backed one throws `IllegalArgumentException`.
 *
 * The capacity must be exactly [bytes]`.size`: `attachDictionary` reads the whole capacity and
 * ignores position/limit, so trailing slack from an over-allocated buffer is treated as dictionary
 * content and every decode then fails with `IOException: corrupted input`.
 */
fun toDirectByteBuffer(bytes: ByteArray): ByteBuffer =
	ByteBuffer.allocateDirect(bytes.size).apply {
		put(bytes)
		flip()
	}

/**
 * Loads the shared Brotli dictionary every `compression = 'brotli'` Content row in [db] is
 * compressed against (see ADFA-5153). Returns null (logged) when the database *definitively* has
 * no dictionary, which tells callers to read and write plain, dictionary-free brotli instead.
 *
 * Both the reader and the writer of `Content` call this, so the two cannot disagree about whether
 * a given database's brotli rows carry a dictionary -- which is the whole point: a row compressed
 * against a dictionary the reader does not attach is undecodable, and vice versa.
 *
 * The gate is the MAJOR version the database declares in ADFA-5220's version table, not the
 * presence of a `CompressionDictionary` table: table sniffing infers a whole content format from
 * one table's existence, and gets it wrong in both directions -- a database carrying the table but
 * *unmigrated* content would have every plain row read as dictionary-compressed. Below
 * [DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY] the dictionary is neither
 * read nor attached.
 *
 * The `CompressionDictionary` checks below still run, for a database that declares a new-enough
 * version but has no usable dictionary row: without them the data query would raise "no such
 * table", which callers correctly read as transient and would then retry forever.
 *
 * Deliberately does *not* catch exceptions itself: an unexpected `SQLiteException`/IO failure is
 * likely transient, and callers must be able to tell that apart from a definitive absence. Caching
 * a transient failure as "no dictionary" would permanently disable dictionary handling for the
 * rest of this database's lifetime; writing plain rows because of one would corrupt content the
 * reader can never decode.
 */
fun loadCompressionDictionary(db: SQLiteDatabase): ByteBuffer? {
	val majorVersion = DatabaseVersionResolver.resolveMajorVersion(db)
	if (majorVersion == null || majorVersion < DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY) {
		Log.w(
			TAG,
			"Database declares documentation version ${majorVersion ?: "none"}, below " +
				"${DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY}; " +
				"brotli content is handled without a dictionary.",
		)
		return null
	}

	val tableExists =
		db
			.rawQuery(
				"SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'CompressionDictionary'",
				null,
			).use { it.moveToFirst() }
	if (!tableExists) {
		Log.w(TAG, "CompressionDictionary table not found; brotli content is handled without a dictionary.")
		return null
	}

	return db.rawQuery("SELECT data FROM CompressionDictionary WHERE id = 1", null).use { cursor ->
		if (!cursor.moveToFirst()) {
			Log.w(TAG, "CompressionDictionary table is empty; brotli content is handled without a dictionary.")
			return null
		}
		val bytes = cursor.getBlob(0)
		if (bytes == null) {
			Log.w(TAG, "CompressionDictionary row has a NULL data column; brotli content is handled without a dictionary.")
			return null
		}
		// An empty blob would yield a 0-capacity buffer, which attachDictionary rejects --
		// every row's dictionary decode would then fail with nothing above DEBUG to say why.
		if (bytes.isEmpty()) {
			Log.w(TAG, "CompressionDictionary row has an empty data column; brotli content is handled without a dictionary.")
			return null
		}
		toDirectByteBuffer(bytes)
	}
}

/**
 * Compresses and decompresses documentation `Content` blobs against [dictionary], the shared Brotli
 * dictionary from the database those blobs live in (see [loadCompressionDictionary]). A null
 * [dictionary] means plain, dictionary-free brotli, which is what a database predating ADFA-5153
 * needs.
 *
 * One instance per database, reused across rows: preparing the dictionary for the encoder costs a
 * few milliseconds and the result is safe to share across streams.
 */
class BrotliDictionaryCodec(
	private val dictionary: ByteBuffer?,
) {
	// Only the encoder needs the dictionary in prepared form, so a decode-only user (WebServer)
	// never pays for building it. `generate` advances the buffer's position to its limit, so it
	// gets a duplicate: the original is shared with attachDictionary, which reads the whole
	// capacity and would be unaffected, but leaving a caller's long-lived buffer drained is a trap
	// for the next reader of it.
	private val preparedDictionary: PreparedDictionary? by lazy {
		dictionary?.let { PreparedDictionaryGenerator.generate(it.duplicate()) }
	}

	/**
	 * Compresses [input] at the quality and window size the offline documentation pipeline uses,
	 * so content contributed at runtime is stored the same way as content built ahead of time.
	 */
	fun compress(input: ByteArray): ByteArray {
		ensureBrotliAvailable()
		val out = ByteArrayOutputStream(input.size)
		BrotliOutputStream(out, encoderParameters).use { stream ->
			preparedDictionary?.let { stream.attachDictionary(it) }
			stream.write(input)
		}
		return out.toByteArray()
	}

	/**
	 * Decompresses a `Content` blob read from the same database [dictionary] came from.
	 *
	 * Throws `IOException` when [input] was not compressed the way that database's rows are --
	 * a dictionary-compressed stream decoded without one leaves backward distances out of bounds,
	 * which any spec-compliant decoder rejects. Note the converse is *not* detectable: attaching
	 * the *wrong* dictionary decodes without error to different bytes than were compressed, so
	 * decode success is never evidence that the right dictionary was used.
	 */
	fun decompress(input: InputStream): ByteArray {
		ensureBrotliAvailable()
		return BrotliInputStream(input).use { stream ->
			dictionary?.let { stream.attachDictionary(it) }
			stream.readBytes()
		}
	}

	/**
	 * Loads brotli4j's native library if nothing else has yet, and turns its absence into a failed
	 * request rather than a dead app.
	 *
	 * Nothing here owns that load: it happens as a side effect of `AssetsInstallationHelper`'s
	 * install or `ToolsManager`'s tooling-jar update, neither of which runs on an ordinary cold
	 * start. A process that skips both -- Android restarting the app straight into the editor, say
	 * -- reaches the first brotli row with the natives unregistered, and `DecoderJNI.nativeCreate`
	 * raises `UnsatisfiedLinkError`. Being an Error rather than an Exception, that escapes the
	 * caller's catch and kills the app from a coroutine worker instead of failing one request
	 * (observed on-device, 20-Aug).
	 *
	 * Referencing [Brotli4jLoader] triggers the static init that performs the load, so this call is
	 * the warm-up; afterwards `ensureAvailability` is a single static null-check, cheap enough to
	 * leave on the per-row path rather than tracking "warmed" state of our own.
	 */
	private fun ensureBrotliAvailable() {
		try {
			Brotli4jLoader.ensureAvailability()
		} catch (e: UnsatisfiedLinkError) {
			throw IOException("brotli4j's native library is unavailable, so brotli content cannot be handled", e)
		}
	}

	companion object {
		// Matches OfflineDocumentationTools' encode settings, so a row written on-device is
		// indistinguishable in size and decodability from one built by that pipeline.
		private val encoderParameters: Encoder.Parameters by lazy {
			Brotli4jLoader.ensureAvailability()
			Encoder.Parameters().setQuality(11).setWindow(24)
		}
	}
}
