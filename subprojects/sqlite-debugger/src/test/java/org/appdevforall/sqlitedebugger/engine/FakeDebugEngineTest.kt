package org.appdevforall.sqlitedebugger.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FakeDebugEngineTest {
	/**
	 * The fake fills a batch up to maxBytes as the native buffer does, so a test that sizes a batch to the
	 * byte gets the real cut. events.bin holds records the C layer wrote, a ROW event among them.
	 */
	@Test
	fun `a batch ends where the native records would`() {
		val bytes = checkNotNull(javaClass.getResourceAsStream("events.bin")).use { it.readBytes() }
		val events = EventDecoder.decode(bytes, bytes.size)
		assertThat(events.map { it.kind }).contains(EventKind.ROW)
		val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		val recordBytes = mutableListOf<Int>()
		var offset = 0
		while (offset < bytes.size) {
			recordBytes += buffer.getInt(offset)
			offset += recordBytes.last()
		}
		assertThat(recordBytes).hasSize(events.size)

		val engine = FakeDebugEngine()
		events.forEach { engine.addEvent(it) }
		var upTo = 0
		recordBytes.forEachIndexed { i, size ->
			upTo += size
			assertThat(engine.readEvents(0, upTo).events).hasSize(i + 1)
			assertThat(engine.readEvents(0, upTo - 1).events).hasSize(i)
		}
	}
}
