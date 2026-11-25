package org.appdevforall.sqlitedebugger.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EventDecoderTest {
	// events.bin is what sqlitedbg_read_events copied out in sqlitedbg_test.c (test.sh --update-fixture);
	// events.tsv is the same records as the C struct reads them: seq, kind, connection, thread, time,
	// duration, flags, sql.
	private val bytes = resource("events.bin")
	private val table =
		String(resource("events.tsv"), Charsets.UTF_8)
			.trimEnd('\n')
			.split('\n')
			.map { it.split('\t', limit = 8) }

	private fun resource(name: String): ByteArray =
		checkNotNull(javaClass.getResourceAsStream(name)) { "missing fixture $name" }.use { it.readBytes() }

	@Test
	fun `decodes every field of the record the C layer wrote`() {
		val events = EventDecoder.decode(bytes, bytes.size)

		assertThat(events).hasSize(table.size)
		events.zip(table).forEach { (event, row) ->
			assertThat(event.seq).isEqualTo(row[0].toLong())
			assertThat(event.kind.nativeValue).isEqualTo(row[1].toInt())
			assertThat(event.connectionId).isEqualTo(row[2].toLong())
			assertThat(event.threadId).isEqualTo(row[3].toLong())
			assertThat(event.timeEpochNanos).isEqualTo(row[4].toLong())
			assertThat(event.durationNanos).isEqualTo(if (event.kind == EventKind.PROFILE) row[5].toLong() else null)
			assertThat(event.truncated).isEqualTo(row[6].toInt() and 1 != 0)
			assertThat(event.expanded).isEqualTo(row[6].toInt() and 2 != 0)
			assertThat(event.sql).isEqualTo(row[7])
		}
	}

	@Test
	fun `the fixture covers every kind and flag and a wide id`() {
		val events = EventDecoder.decode(bytes, bytes.size)

		assertThat(events.map { it.kind }.toSet()).containsExactlyElementsIn(EventKind.values().toList())
		assertThat(events.any { it.truncated }).isTrue()
		assertThat(events.any { it.expanded }).isTrue()
		assertThat(events.any { it.sql.startsWith("--") }).isTrue()
		// multi-byte text survives, including a character outside the BMP
		assertThat(events.any { it.sql.contains("héllo € 😀") }).isTrue()
		assertThat(events.map { it.seq }).isInOrder()
	}

	@Test
	fun `a prefix ending on a record boundary decodes to those records`() {
		val firstRecordBytes = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0)

		assertThat(EventDecoder.decode(bytes, firstRecordBytes)).hasSize(1)
		assertThat(EventDecoder.decode(bytes, 0)).isEmpty()
	}

	@Test
	fun `a cut-off record is refused`() {
		val firstRecordBytes = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0)

		assertThrows(IllegalArgumentException::class.java) { EventDecoder.decode(bytes, firstRecordBytes - 8) }
		assertThrows(IllegalArgumentException::class.java) { EventDecoder.decode(bytes, EventDecoder.HEADER_BYTES - 1) }
		assertThrows(IllegalArgumentException::class.java) { EventDecoder.decode(bytes, bytes.size + 1) }
	}

	@Test
	fun `a malformed header is refused`() {
		fun patched(
			offset: Int,
			value: Int,
		): ByteArray {
			val copy = bytes.copyOf()
			ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
			return copy
		}

		// record_bytes not a multiple of 8, smaller than the header, and an unknown kind
		for (broken in listOf(patched(0, 60), patched(0, 48), patched(4, 9), patched(4, 0))) {
			assertThrows(IllegalArgumentException::class.java) { EventDecoder.decode(broken, broken.size) }
		}
		// sql_bytes longer than the record holds
		val big = patched(52, 100000)
		assertThrows(IllegalArgumentException::class.java) { EventDecoder.decode(big, big.size) }
		val negative = patched(52, -1)
		assertThrows(IllegalArgumentException::class.java) { EventDecoder.decode(negative, negative.size) }
	}
}
