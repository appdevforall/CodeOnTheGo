package org.appdevforall.codeonthego.indexing.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class IndexingServiceManagerTest {

    class TestService(override val id: String) : IndexingService {
        override val providedKeys = emptyList<IndexKey<*>>()
        var initialized = false
        var buildCompleted = false
        var closed = false

        override suspend fun initialize(registry: IndexRegistry) {
            initialized = true
        }

        override suspend fun onBuildCompleted() {
            buildCompleted = true
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `register adds service retrievable by id`() {
        val manager = IndexingServiceManager()
        val svc = TestService("svc-a")
        manager.register(svc)
        assertThat(manager.getService("svc-a")).isSameInstanceAs(svc)
        manager.close()
    }

    @Test
    fun `duplicate registration is silently ignored`() {
        val manager = IndexingServiceManager()
        val svc1 = TestService("svc-a")
        val svc2 = TestService("svc-a")
        manager.register(svc1)
        manager.register(svc2)
        assertThat(manager.getService("svc-a")).isSameInstanceAs(svc1)
        manager.close()
    }

    @Test
    fun `allServices returns all registered services`() {
        val manager = IndexingServiceManager()
        manager.register(TestService("a"))
        manager.register(TestService("b"))
        manager.register(TestService("c"))
        assertThat(manager.allServices()).hasSize(3)
        manager.close()
    }

    @Test
    fun `getService returns null for unregistered id`() {
        val manager = IndexingServiceManager()
        assertThat(manager.getService("unknown")).isNull()
        manager.close()
    }

    @Test
    fun `close calls close on each registered service`() {
        val manager = IndexingServiceManager()
        val svc1 = TestService("a")
        val svc2 = TestService("b")
        manager.register(svc1)
        manager.register(svc2)
        manager.close()
        assertThat(svc1.closed).isTrue()
        assertThat(svc2.closed).isTrue()
    }

    @Test
    fun `close clears services list`() {
        val manager = IndexingServiceManager()
        manager.register(TestService("a"))
        manager.close()
        assertThat(manager.allServices()).isEmpty()
    }

    @Test
    fun `registry is accessible and starts empty`() {
        val manager = IndexingServiceManager()
        assertThat(manager.registry).isNotNull()
        assertThat(manager.registry.registeredKeys()).isEmpty()
        manager.close()
    }

    @Test
    fun `onBuildCompleted before initialization does not throw`() {
        val manager = IndexingServiceManager()
        val svc = TestService("a")
        manager.register(svc)
        // Should be a no-op and not throw
        manager.onBuildCompleted()
        manager.close()
    }

    @Test
    fun `onSourceChanged before initialization is a no-op`() {
        val manager = IndexingServiceManager()
        manager.register(TestService("a"))
        manager.onSourceChanged()
        manager.close()
    }

    @Test
    fun `close after close does not throw`() {
        val manager = IndexingServiceManager()
        manager.close()
        manager.close() // second close should be safe
    }
}
