package com.itsaky.androidide.lookup.internal

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lookup.LookupTest.TestService
import com.itsaky.androidide.lookup.LookupTest.TestServiceImpl
import com.itsaky.androidide.lookup.ServiceRegisteredException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * @author Akash Yadav
 */
@RunWith(JUnit4::class)
class DefaultLookupTest {

	@Test
	fun `test key registration when service registered with update func`() {
		val lookup = DefaultLookup()
		val service = TestServiceImpl()
		assertThat(lookup.keyTable[TestService::class.java]).isNull()

		lookup.update(TestService::class.java, service)
		assertThat(lookup.keyTable[TestService::class.java]).isNotNull()

		assertThat(lookup.lookup(TestService::class.java)).isEqualTo(service)
	}

	@Test
	fun `test register throws exception when re-registering a service`() {
		val lookup = DefaultLookup()
		val service = TestServiceImpl()

		lookup.register(TestService::class.java, service)
		assertThat(lookup.lookup(TestService::class.java)).isNotNull()

		assertThrows(ServiceRegisteredException::class.java) {
			lookup.register(TestService::class.java, service)
		}
	}
}