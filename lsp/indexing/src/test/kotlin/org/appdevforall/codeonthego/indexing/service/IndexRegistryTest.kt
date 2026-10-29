package org.appdevforall.codeonthego.indexing.service

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.Closeable

@RunWith(JUnit4::class)
class IndexRegistryTest {
	private val keyA = IndexKey<String>("index-a")
	private val keyB = IndexKey<Int>("index-b")

	@Test
	fun `get returns null before registration`() {
		val registry = IndexRegistry()
		assertThat(registry.get(keyA)).isNull()
	}

	@Test
	fun `register and get return same instance`() {
		val registry = IndexRegistry()
		registry.register(keyA, "hello")
		assertThat(registry.get(keyA)).isEqualTo("hello")
	}

	@Test
	fun `require throws if not registered`() {
		val registry = IndexRegistry()
		try {
			registry.require(keyA)
			error("expected exception not thrown")
		} catch (e: IllegalStateException) {
			assertThat(e.message).contains("index-a")
		}
	}

	@Test
	fun `require returns value when registered`() {
		val registry = IndexRegistry()
		registry.register(keyA, "world")
		assertThat(registry.require(keyA)).isEqualTo("world")
	}

	@Test
	fun `isRegistered reflects current state`() {
		val registry = IndexRegistry()
		assertThat(registry.isRegistered(keyA)).isFalse()
		registry.register(keyA, "x")
		assertThat(registry.isRegistered(keyA)).isTrue()
	}

	@Test
	fun `registeredKeys returns all registered key names`() {
		val registry = IndexRegistry()
		registry.register(keyA, "x")
		registry.register(keyB, 42)
		assertThat(registry.registeredKeys()).containsExactly("index-a", "index-b")
	}

	@Test
	fun `ifAvailable returns null when not registered`() {
		val registry = IndexRegistry()
		val result = registry.ifAvailable(keyA) { it.length }
		assertThat(result).isNull()
	}

	@Test
	fun `ifAvailable invokes block when registered`() {
		val registry = IndexRegistry()
		registry.register(keyA, "hello")
		val result = registry.ifAvailable(keyA) { it.length }
		assertThat(result).isEqualTo(5)
	}

	@Test
	fun `re-registering replaces old value`() {
		val registry = IndexRegistry()
		registry.register(keyA, "first")
		registry.register(keyA, "second")
		assertThat(registry.get(keyA)).isEqualTo("second")
	}

	@Test
	fun `onAvailable notifies immediately if already registered`() {
		val registry = IndexRegistry()
		registry.register(keyA, "present")

		var received: String? = null
		registry.onAvailable(keyA) { received = it }

		assertThat(received).isEqualTo("present")
	}

	@Test
	fun `onAvailable notifies after subsequent registration`() {
		val registry = IndexRegistry()

		var received: String? = null
		registry.onAvailable(keyA) { received = it }
		assertThat(received).isNull()

		registry.register(keyA, "later")
		assertThat(received).isEqualTo("later")
	}

	@Test
	fun `onAvailable listener is called on re-registration`() {
		val registry = IndexRegistry()
		registry.register(keyA, "first")

		val received = mutableListOf<String>()
		registry.onAvailable(keyA) { received.add(it) }

		registry.register(keyA, "second")

		assertThat(received).containsExactly("first", "second")
	}

	@Test
	fun `unregister returns old value`() {
		val registry = IndexRegistry()
		registry.register(keyA, "value")
		val old = registry.unregister(keyA)
		assertThat(old).isEqualTo("value")
		assertThat(registry.get(keyA)).isNull()
	}

	@Test
	fun `unregister returns null if not registered`() {
		val registry = IndexRegistry()
		assertThat(registry.unregister(keyA)).isNull()
	}

	@Test
	fun `close clears all indexes and listeners`() {
		val registry = IndexRegistry()
		registry.register(keyA, "x")
		registry.register(keyB, 42)
		registry.close()
		assertThat(registry.registeredKeys()).isEmpty()
	}

	@Test
	fun `close calls close on Closeable indexes`() {
		val registry = IndexRegistry()
		val keyC = IndexKey<CloseableValue>("index-c")
		val closeable = CloseableValue()
		registry.register(keyC, closeable)
		registry.close()
		assertThat(closeable.closed).isTrue()
	}

	@Test
	fun `multiple keys coexist independently`() {
		val registry = IndexRegistry()
		registry.register(keyA, "text")
		registry.register(keyB, 99)
		assertThat(registry.get(keyA)).isEqualTo("text")
		assertThat(registry.get(keyB)).isEqualTo(99)
	}

	@Test
	fun `registeredKeys returns empty set when nothing registered`() {
		val registry = IndexRegistry()
		assertThat(registry.registeredKeys()).isEmpty()
	}

	@Test
	fun `registerIfAbsent keeps and does not close an existing index`() {
		val registry = IndexRegistry()
		val keyC = IndexKey<CloseableValue>("index-c")
		val existing = CloseableValue()
		registry.register(keyC, existing)
		var created = false

		val got =
			registry.registerIfAbsent(keyC) {
				created = true
				CloseableValue()
			}

		assertThat(got).isSameInstanceAs(existing)
		assertThat(registry.get(keyC)).isSameInstanceAs(existing)
		assertThat(existing.closed).isFalse()
		assertThat(created).isFalse()
	}

	@Test
	fun `registerIfAbsent registers and notifies when absent`() {
		val registry = IndexRegistry()
		val received = mutableListOf<String>()
		registry.onAvailable(keyA) { received += it }

		val got = registry.registerIfAbsent(keyA) { "created" }

		assertThat(got).isEqualTo("created")
		assertThat(registry.get(keyA)).isEqualTo("created")
		assertThat(received).containsExactly("created")
	}

	@Test
	fun `registerIfAbsent after close does not create and throws`() {
		val registry = IndexRegistry()
		registry.close()
		var created = false

		assertThrows(IndexRegistryClosedException::class.java) {
			registry.registerIfAbsent(keyA) {
				created = true
				"created"
			}
		}

		assertThat(created).isFalse()
		assertThat(registry.get(keyA)).isNull()
	}

	@Test
	fun `registerIfAbsent after the session ended does not create and throws`() {
		val registry = IndexRegistry()
		registry.lifetime.end()
		var created = false

		assertThrows(IndexRegistryClosedException::class.java) {
			registry.registerIfAbsent(keyA) {
				created = true
				"created"
			}
		}

		assertThat(created).isFalse()
	}

	@Test
	fun `require of a missing index after the session ended throws the closed exception`() {
		val registry = IndexRegistry()
		registry.lifetime.end()

		assertThrows(IndexRegistryClosedException::class.java) { registry.require(keyA) }
	}

	@Test
	fun `register after close closes the given index and throws`() {
		val registry = IndexRegistry()
		registry.close()
		val keyC = IndexKey<CloseableValue>("index-c")
		val index = CloseableValue()

		assertThrows(IndexRegistryClosedException::class.java) { registry.register(keyC, index) }

		assertThat(index.closed).isTrue()
		assertThat(registry.get(keyC)).isNull()
	}

	@Test
	fun `require after close throws the closed exception`() {
		val registry = IndexRegistry()
		registry.register(keyA, "a")
		registry.close()

		assertThrows(IndexRegistryClosedException::class.java) { registry.require(keyA) }
	}

	class CloseableValue : Closeable {
		var closed = false

		override fun close() {
			closed = true
		}
	}
}
