package com.itsaky.androidide.lookup.internal

import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lookup.ServiceRegisteredException
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class DefaultLookupTest {

    @Test
    fun `update() registers unregistered service`() {
        val lookup: Lookup = DefaultLookup()
        val testService = "newTestToUpdate"

        lookup.update(String::class.java, testService)

        assertEquals(testService, lookup.lookup(String::class.java))
    }

    @Test
    fun `update() updates existing service`() {
        val lookup: Lookup = DefaultLookup()
        val testService1 = "test1"
        val testService2 = "test2"

        lookup.register(String::class.java, testService1)
        lookup.update(String::class.java, testService2)

        assertEquals(testService2, lookup.lookup(String::class.java))
    }

    @Test
    fun `register() registers new service successfully`() {
        val lookup: Lookup = DefaultLookup()
        val testService = "newTestToRegister"

        lookup.register(String::class.java, testService)

        assertEquals(testService, lookup.lookup(String::class.java))
    }

    @Test
    fun `register() throws exception if service is already registered`() {
        val lookup: Lookup = DefaultLookup()
        val testService = "testToReregister"

        lookup.register(String::class.java, testService)

        assertFailsWith<ServiceRegisteredException> {
            lookup.register(String::class.java, testService)
        }
    }
}
