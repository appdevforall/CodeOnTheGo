package org.appdevforall.codeonthego.indexing

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.api.Indexable
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(JUnit4::class)
class FilteredIndexConcurrencyTest {
	data class Entry(
		override val key: String,
		override val sourceId: String,
	) : Indexable

	private val descriptor =
		object : IndexDescriptor<Entry> {
			override val name = "test_filtered_concurrency"
			override val fields = listOf(IndexField("key"))

			override fun fieldValues(entry: Entry) = mapOf("key" to entry.key)

			override fun serialize(entry: Entry) = "${entry.key}|${entry.sourceId}".toByteArray()

			override fun deserialize(bytes: ByteArray): Entry {
				val parts = String(bytes).split("|")
				return Entry(parts[0], parts[1])
			}
		}

	@Test(timeout = 30_000)
	fun `a reader never sees a half-replaced active set`() {
		val filtered = FilteredIndex(InMemoryIndex(descriptor))
		val first = (1..50).map { "/libs/a$it.jar" }.toSet()
		val second = (1..50).map { "/libs/b$it.jar" }.toSet()
		filtered.setActiveSources(first)

		val stop = AtomicBoolean(false)
		val writer =
			thread {
				while (!stop.get()) {
					filtered.setActiveSources(second)
					filtered.setActiveSources(first)
				}
			}

		val torn =
			try {
				(1..200_000).count {
					val seen = filtered.activeSources()
					seen != first && seen != second
				}
			} finally {
				stop.set(true)
				writer.join()
			}

		assertThat(torn).isEqualTo(0)
	}
}
