package com.itsaky.androidide.utils

import android.database.sqlite.SQLiteDatabase
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder
import com.aayushatharva.brotli4j.encoder.PreparedDictionary
import com.aayushatharva.brotli4j.encoder.PreparedDictionaryGenerator
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

private val log = LoggerFactory.getLogger("DocumentationCompression")

// brotli4j's PreparedDictionaryGenerator rejects a shorter dictionary with "src is too short"
// (measured: 7 bytes throws, 8 round-trips). The decoder has no such floor, so without this the
// failure lands only on the writer, as an IllegalArgumentException from inside a lazy.
private const val MIN_DICTIONARY_BYTES = 8

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
		log.warn(
			"Database declares documentation version {}, below {}; brotli content is handled without a dictionary.",
			majorVersion ?: "none",
			DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY,
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
		log.warn("CompressionDictionary table not found; brotli content is handled without a dictionary.")
		return null
	}

	return db.rawQuery("SELECT data FROM CompressionDictionary WHERE id = 1", null).use { cursor ->
		if (!cursor.moveToFirst()) {
			log.warn("CompressionDictionary table is empty; brotli content is handled without a dictionary.")
			return null
		}
		val bytes = cursor.getBlob(0)
		if (bytes == null) {
			log.warn("CompressionDictionary row has a NULL data column; brotli content is handled without a dictionary.")
			return null
		}
		// An empty blob would yield a 0-capacity buffer, which attachDictionary rejects, and the
		// encoder refuses anything under MIN_DICTIONARY_BYTES outright. Either way a truncated
		// dictionary is not usable, and treating it as absent keeps reader and writer agreeing --
		// where letting it through would fail every decode, or every compress, with nothing above
		// DEBUG to say why.
		if (bytes.size < MIN_DICTIONARY_BYTES) {
			log.warn(
				"CompressionDictionary row holds {} bytes, below the {} the encoder requires; " +
					"brotli content is handled without a dictionary.",
				bytes.size,
				MIN_DICTIONARY_BYTES,
			)
			return null
		}
		toDirectByteBuffer(bytes)
	}
}

/**
 * Whether [db] declares a version whose brotli `Content` rows are dictionary-compressed.
 *
 * [loadCompressionDictionary] returns null both for a database that legitimately has no dictionary
 * (below [DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY]) and for one that
 * should have a usable dictionary but does not -- table dropped, row missing, blob truncated. A
 * reader cannot tell those apart usefully; it decodes plain either way and every dictionary-
 * compressed row simply fails. A *writer* must, because the second case is a damaged database that
 * can be repaired in place: rows written plain into it would still be plain afterwards, and would
 * then be undecodable with no missing-rows check to catch them.
 */
fun expectsCompressionDictionary(db: SQLiteDatabase): Boolean {
	val majorVersion = DatabaseVersionResolver.resolveMajorVersion(db)
	return majorVersion != null && majorVersion >= DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY
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
	init {
		// The encoder would quietly accept a heap buffer (it copies into a direct one of its own),
		// but attachDictionary on the decode side throws IllegalArgumentException. Without this a
		// heap dictionary writes genuinely dictionary-compressed rows that every later read
		// rejects -- and unchecked, so handleClient turns it into a 500 naming neither the row nor
		// the dictionary. Fail at construction, where the caller can see why.
		require(dictionary == null || dictionary.isDirect) {
			"dictionary must be a direct ByteBuffer (see toDirectByteBuffer); a heap buffer compresses but cannot decompress"
		}
		require(dictionary == null || dictionary.capacity() >= MIN_DICTIONARY_BYTES) {
			"dictionary must be at least $MIN_DICTIONARY_BYTES bytes, was ${dictionary?.capacity()}"
		}
	}

	// Only the encoder needs the dictionary in prepared form, so a decode-only user (WebServer)
	// never pays for building it. `generate` advances the buffer's position to its limit, so it
	// gets a duplicate: the original is shared with attachDictionary, which reads the whole
	// capacity and would be unaffected, but leaving a caller's long-lived buffer drained is a trap
	// for the next reader of it.
	private val preparedDictionary: PreparedDictionary? by lazy {
		dictionary?.let { PreparedDictionaryGenerator.generate(it.duplicate()) }
	}

	/**
	 * Builds the encoder's prepared dictionary now rather than on the first [compress].
	 *
	 * It is `by lazy` so a decode-only user never pays for it, but that defers a ~780 KB direct
	 * allocation to whenever compression first happens -- which for the plugin installer is inside
	 * an open write transaction. Callers holding a lock around their compression should warm it
	 * first.
	 */
	fun warmUp() {
		ensureBrotliAvailable()
		preparedDictionary
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
	 * Throws `IOException` when [input] *referenced* a dictionary that is not attached: those
	 * backward distances reach outside the window, which any spec-compliant decoder rejects.
	 *
	 * Note the qualifier. What matters is whether the stream actually matched into the dictionary,
	 * not whether one was attached when it was written. Content with no such matches -- an already
	 * compressed image, a block of noise -- round-trips identically either way (measured both
	 * directions; see BrotliDictionaryDecodeTest). So a mismatched row set fails *non-uniformly*:
	 * a plugin's HTML raises IOException while its incompressible assets keep serving.
	 *
	 * A *wrong* dictionary is a different matter, and is not reliably detectable. One of a
	 * different length, or with nothing valid at the offsets the stream references, usually
	 * throws -- but one of the same length holding plausible bytes there decodes cleanly to
	 * content that is simply wrong (verified: see BrotliDictionaryDecodeTest). So neither a throw
	 * nor a success is evidence about *which* dictionary was used.
	 *
	 * Takes ownership of [input] and closes it, including when the decoder fails to start.
	 */
	fun decompress(input: InputStream): ByteArray {
		ensureBrotliAvailable()
		// BrotliInputStream's constructor allocates native state and can throw, which would leave
		// `input` open if it were built inside the use{} it is the subject of.
		val stream =
			try {
				BrotliInputStream(input)
			} catch (e: Throwable) {
				input.close()
				throw e
			}
		return stream.use {
			dictionary?.let { dict -> it.attachDictionary(dict) }
			it.readBytes()
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
			Encoder.Parameters().setQuality(11).setWindow(24)
		}
	}
}
