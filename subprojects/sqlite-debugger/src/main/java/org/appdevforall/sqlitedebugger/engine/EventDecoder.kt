package org.appdevforall.sqlitedebugger.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the records `sqlitedbg_read_events` copies out. Mirrors `sqlitedbg_event_record` and the
 * row layout (`sqlitedbg_row_header`, `sqlitedbg_row_value`) in `sqlitedbg.h`: change both together.
 * Little-endian, because the CPU is on every ABI we build.
 */
internal object EventDecoder {
	/** `sizeof(sqlitedbg_event_record)`. */
	const val HEADER_BYTES = 72

	private const val OFFSET_RECORD_BYTES = 0
	private const val OFFSET_KIND = 4
	private const val OFFSET_SEQ = 8
	private const val OFFSET_CONNECTION_ID = 16
	private const val OFFSET_THREAD_ID = 24
	private const val OFFSET_TIME_NS = 32
	private const val OFFSET_DURATION_NS = 40
	private const val OFFSET_FLAGS = 48
	private const val OFFSET_DATA_BYTES = 52
	private const val OFFSET_ROWS = 56
	private const val OFFSET_CHANGES = 64

	private const val FLAG_TRUNCATED = 1
	private const val FLAG_EXPANDED = 2
	private const val FLAG_ROWS_CUT = 4

	internal const val ROW_HEADER_BYTES = 8
	internal const val ROW_VALUE_BYTES = 12

	// SQLite's fundamental types, as sqlite3_column_type returns them.
	private const val SQLITE_INTEGER = 1
	private const val SQLITE_FLOAT = 2
	private const val SQLITE_TEXT = 3
	private const val SQLITE_BLOB = 4
	private const val SQLITE_NULL = 5

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
			val dataBytes = buffer.getInt(offset + OFFSET_DATA_BYTES)
			require(dataBytes >= 0 && recordBytes >= 0 && recordBytes % 8 == 0 && dataBytes <= recordBytes - HEADER_BYTES) {
				"record at $offset has record_bytes=$recordBytes data_bytes=$dataBytes"
			}
			require(recordBytes <= length - offset) { "record at $offset runs past the end of the data" }
			val kindValue = buffer.getInt(offset + OFFSET_KIND)
			val kind =
				EventKind.values().firstOrNull { it.nativeValue == kindValue }
					?: throw IllegalArgumentException("record at $offset has unknown kind $kindValue")
			val flags = buffer.getInt(offset + OFFSET_FLAGS)
			val dataStart = offset + HEADER_BYTES
			// -1 in the record: does not apply, or the extension did not see the statement start
			val rows = buffer.getLong(offset + OFFSET_ROWS).takeIf { it >= 0 && (kind == EventKind.PROFILE || kind == EventKind.ROW) }
			val changes = buffer.getLong(offset + OFFSET_CHANGES).takeIf { it >= 0 && kind == EventKind.PROFILE }
			events +=
				Event(
					seq = buffer.getLong(offset + OFFSET_SEQ),
					kind = kind,
					connectionId = buffer.getLong(offset + OFFSET_CONNECTION_ID),
					threadId = buffer.getLong(offset + OFFSET_THREAD_ID),
					timeEpochNanos = buffer.getLong(offset + OFFSET_TIME_NS),
					durationNanos = if (kind == EventKind.PROFILE) buffer.getLong(offset + OFFSET_DURATION_NS) else null,
					sql = if (kind == EventKind.ROW) "" else String(bytes, dataStart, dataBytes, Charsets.UTF_8),
					truncated = flags and FLAG_TRUNCATED != 0,
					expanded = flags and FLAG_EXPANDED != 0,
					rows = rows,
					changes = changes,
					rowsCut = kind == EventKind.PROFILE && flags and FLAG_ROWS_CUT != 0,
					row = if (kind == EventKind.ROW) decodeRow(bytes, buffer, dataStart, dataBytes) else null,
				)
			offset += recordBytes
		}
		return events
	}

	private fun decodeRow(
		bytes: ByteArray,
		buffer: ByteBuffer,
		start: Int,
		length: Int,
	): RowValues {
		require(length >= ROW_HEADER_BYTES) { "row at $start is shorter than its header" }
		val end = start + length
		val columns = buffer.getInt(start)
		val kept = buffer.getInt(start + 4)
		require(columns >= 0 && kept in 0..columns) { "row at $start has columns=$columns kept=$kept" }
		val values = ArrayList<Any?>(kept)
		val cut = HashSet<Int>()
		var pos = start + ROW_HEADER_BYTES
		repeat(kept) { i ->
			require(end - pos >= ROW_VALUE_BYTES) { "row at $start: value $i is cut off" }
			val type = buffer.get(pos).toInt() and 0xff
			val isCut = buffer.get(pos + 1).toInt() != 0
			val stored = buffer.getInt(pos + 4)
			val full = buffer.getInt(pos + 8)
			pos += ROW_VALUE_BYTES
			require(stored in 0..(end - pos) && full >= stored && isCut == (stored < full)) {
				"row at $start: value $i has stored_bytes=$stored full_bytes=$full cut=$isCut"
			}
			values +=
				when (type) {
					SQLITE_INTEGER, SQLITE_FLOAT -> {
						require(stored == 8) { "row at $start: number $i is $stored bytes" }
						if (type == SQLITE_INTEGER) buffer.getLong(pos) else buffer.getDouble(pos)
					}

					SQLITE_TEXT -> {
						String(bytes, pos, stored, Charsets.UTF_8)
					}

					SQLITE_BLOB -> {
						bytes.copyOfRange(pos, pos + stored)
					}

					SQLITE_NULL -> {
						require(stored == 0) { "row at $start: null $i has $stored bytes" }
						null
					}

					else -> {
						throw IllegalArgumentException("row at $start: value $i has unknown type $type")
					}
				}
			if (isCut) cut += i
			pos += stored
		}
		require(pos == end) { "row at $start has ${end - pos} bytes after its last value" }
		return RowValues(columns, values, cut)
	}
}
