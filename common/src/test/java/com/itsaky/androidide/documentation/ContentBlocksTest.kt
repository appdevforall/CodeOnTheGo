package com.itsaky.androidide.documentation

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The pure logic of the ADFA-6683 content blocks: slicing an item out of a decoded block, and the
 * cache of decoded blocks. Serving a page from a real Brotli block is covered in :app, by
 * ContentBlockSourceTest, since this module's tests have no brotli4j native.
 */
class ContentBlocksTest {
	private val block = "firstsecondthird".toByteArray()

	@Test
	fun `an item is its offset and length in the decoded block`() {
		assertThat(slice(offset = 5, length = 6)).isEqualTo("second")
		assertThat(slice(offset = 0, length = 5)).isEqualTo("first")
		assertThat(slice(offset = 11, length = 5)).isEqualTo("third")
	}

	@Test
	fun `an empty item at the end of the block is empty`() {
		assertThat(slice(offset = block.size.toLong(), length = 0)).isEmpty()
	}

	@Test
	fun `an item running past the end of the block is rejected`() {
		val e = assertThrows(IllegalStateException::class.java) { slice(offset = 11, length = 6) }
		assertThat(e).hasMessageThat().contains("content block 9")
	}

	@Test
	fun `a negative offset or length is rejected`() {
		assertThrows(IllegalStateException::class.java) { slice(offset = -1, length = 2) }
		assertThrows(IllegalStateException::class.java) { slice(offset = 2, length = -1) }
	}

	// Narrowed to Int without the 64-bit check, 2^32 + 5 would wrap to 5 and serve "second".
	@Test
	fun `an offset past Int range is rejected, not wrapped`() {
		assertThrows(IllegalStateException::class.java) { slice(offset = (1L shl 32) + 5, length = 6) }
	}

	@Test
	fun `blocks are evicted once their plain bytes pass the budget`() {
		val cache = ContentBlockCache(maxBytes = 10)
		assertThat(cache.put(1, ByteArray(4))).isEqualTo(4L)
		assertThat(cache.put(2, ByteArray(4))).isEqualTo(8L)

		assertThat(cache.put(3, ByteArray(4))).isEqualTo(8L)

		assertThat(cache.get(1)).isNull()
		assertThat(cache.get(2)).isNotNull()
		assertThat(cache.get(3)).isNotNull()
	}

	@Test
	fun `a block filling the budget exactly is kept`() {
		val cache = ContentBlockCache(maxBytes = 10)
		cache.put(1, ByteArray(4))

		assertThat(cache.put(2, ByteArray(10))).isEqualTo(10L)

		assertThat(cache.get(1)).isNull()
		assertThat(cache.get(2)).isNotNull()
	}

	@Test
	fun `the least recently used block goes first, not the oldest`() {
		val cache = ContentBlockCache(maxBytes = 9)
		cache.put(1, ByteArray(3))
		cache.put(2, ByteArray(3))
		cache.put(3, ByteArray(3))
		cache.get(1)

		cache.put(4, ByteArray(3))

		assertThat(cache.get(2)).isNull()
		assertThat(cache.get(1)).isNotNull()
		assertThat(cache.get(3)).isNotNull()
		assertThat(cache.get(4)).isNotNull()
	}

	// OfflineDocumentationTools closes a block before it passes 4 MiB decoded, so that two fit here.
	@Test
	fun `the app's budget holds two blocks at the 4 MiB cap`() {
		val cache = ContentBlockCache(ContentBlockCache.MAX_PLAIN_BYTES)
		cache.put(1, ByteArray(4 * 1024 * 1024))
		cache.put(2, ByteArray(4 * 1024 * 1024))

		assertThat(cache.get(1)).isNotNull()
		assertThat(cache.get(2)).isNotNull()
	}

	@Test
	fun `a block over the whole budget is not cached and evicts nothing`() {
		val cache = ContentBlockCache(maxBytes = 10)
		cache.put(1, ByteArray(4))

		assertThat(cache.put(2, ByteArray(11))).isEqualTo(4L)

		assertThat(cache.get(2)).isNull()
		assertThat(cache.get(1)).isNotNull()
	}

	@Test
	fun `caching a block again replaces it and counts it once`() {
		val cache = ContentBlockCache(maxBytes = 10)
		cache.put(1, ByteArray(4))

		assertThat(cache.put(1, ByteArray(6))).isEqualTo(6L)

		assertThat(cache.get(1)).hasLength(6)
	}

	@Test
	fun `clear drops every block and its bytes`() {
		val cache = ContentBlockCache(maxBytes = 10)
		cache.put(1, ByteArray(4))
		cache.put(2, ByteArray(4))

		cache.clear()

		assertThat(cache.plainBytes()).isEqualTo(0L)
		assertThat(cache.get(1)).isNull()
		assertThat(cache.get(2)).isNull()
		// Counting from zero again, not from the bytes cleared.
		assertThat(cache.put(3, ByteArray(10))).isEqualTo(10L)
	}

	// An access-ordered LinkedHashMap restructures itself on get(), so unsynchronised readers corrupt
	// it or throw ConcurrentModificationException; the byte count must also stay within the budget.
	@Test
	fun `concurrent requests keep the cache within its budget`() {
		val cache = ContentBlockCache(maxBytes = 64)
		val threads = 8
		val pool = Executors.newFixedThreadPool(threads)
		val start = CountDownLatch(1)
		val failures = mutableListOf<Throwable>()
		try {
			val tasks =
				(0 until threads).map { thread ->
					pool.submit {
						start.await()
						try {
							repeat(20_000) { i ->
								val id = ((thread * 31 + i) % 40).toLong()
								if (cache.get(id) == null) cache.put(id, ByteArray(1 + (id % 9).toInt()))
								check(cache.plainBytes() <= 64)
							}
						} catch (e: Throwable) {
							synchronized(failures) { failures.add(e) }
						}
					}
				}
			start.countDown()
			tasks.forEach { it.get(60, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}

		assertThat(failures).isEmpty()
		val cached = (0L until 40L).mapNotNull { cache.get(it) }.sumOf { it.size.toLong() }
		assertThat(cache.plainBytes()).isEqualTo(cached)
	}

	private fun slice(
		offset: Long,
		length: Long,
	) = sliceBlockItem(block, blockId = 9, offset = offset, length = length).toString(Charsets.UTF_8)
}
