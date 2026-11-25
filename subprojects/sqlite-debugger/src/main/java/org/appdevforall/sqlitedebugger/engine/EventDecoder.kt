package org.appdevforall.sqlitedebugger.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the records `sqlitedbg_read_events` copies out. Mirrors `sqlitedbg_event_record` in
 * `sqlitedbg.h`: change both together. Little-endian, because the CPU is on every ABI we build.
 */
internal object EventDecoder {
	/** `sizeof(sqlitedbg_event_record)`. */
	const val HEADER_BYTES = 56

	private const val OFFSET_RECORD_BYTES = 0
	private const val OFFSET_KIND = 4
	private const val OFFSET_SEQ = 8
	private const val OFFSET_CONNECTION_ID = 16
	private const val OFFSET_THREAD_ID = 24
	private const val OFFSET_TIME_NS = 32
	private const val OFFSET_DURATION_NS = 40
	private const val OFFSET_FLAGS = 48
	private const val OFFSET_SQL_BYTES = 52

	private const val FLAG_TRUNCATED = 1
	private const val FLAG_EXPANDED = 2

	/**
	 * The records in the first [length] bytes of [bytes].
	 *
	 * @throws IllegalArgumentException if they are not whole, well-formed records.
	 */
	fun decode(
		bytes: ByteArray,
		length: Int,
	): List<Event> {
		require(length in 0..bytes.size) { "length $length outside 0..${bytes.size}" }
		val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
		val events = ArrayList<Event>()
		var offset = 0
		while (offset < length) {
			require(length - offset >= HEADER_BYTES) { "record at $offset is cut off by the end of the data" }
			val recordBytes = buffer.getInt(offset + OFFSET_RECORD_BYTES)
			val sqlBytes = buffer.getInt(offset + OFFSET_SQL_BYTES)
			require(sqlBytes >= 0 && recordBytes >= 0 && recordBytes % 8 == 0 && sqlBytes <= recordBytes - HEADER_BYTES) {
				"record at $offset has record_bytes=$recordBytes sql_bytes=$sqlBytes"
			}
			require(recordBytes <= length - offset) { "record at $offset runs past the end of the data" }
			val kindValue = buffer.getInt(offset + OFFSET_KIND)
			val kind =
				EventKind.values().firstOrNull { it.nativeValue == kindValue }
					?: throw IllegalArgumentException("record at $offset has unknown kind $kindValue")
			val flags = buffer.getInt(offset + OFFSET_FLAGS)
			events +=
				Event(
					seq = buffer.getLong(offset + OFFSET_SEQ),
					kind = kind,
					connectionId = buffer.getLong(offset + OFFSET_CONNECTION_ID),
					threadId = buffer.getLong(offset + OFFSET_THREAD_ID),
					timeEpochNanos = buffer.getLong(offset + OFFSET_TIME_NS),
					durationNanos = if (kind == EventKind.PROFILE) buffer.getLong(offset + OFFSET_DURATION_NS) else null,
					sql = String(bytes, offset + HEADER_BYTES, sqlBytes, Charsets.UTF_8),
					truncated = flags and FLAG_TRUNCATED != 0,
					expanded = flags and FLAG_EXPANDED != 0,
				)
			offset += recordBytes
		}
		return events
	}
}
