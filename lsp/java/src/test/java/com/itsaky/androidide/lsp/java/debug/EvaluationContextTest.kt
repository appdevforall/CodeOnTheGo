/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.lsp.java.debug

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.java.debug.utils.EvaluationContext
import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.InvalidStackFrameException
import com.sun.jdi.ObjectCollectedException
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class EvaluationContextTest {
	private lateinit var context: EvaluationContext

	@Before
	fun setUp() {
		context = EvaluationContext()
	}

	@After
	fun tearDown() {
		context.close()
	}

	private fun fakeThread(
		isSuspended: Boolean,
		name: String = "test-thread",
	): ThreadReference {
		val handler =
			object : InvocationHandler {
				override fun invoke(
					proxy: Any,
					method: Method,
					args: Array<out Any>?,
				): Any? =
					when (method.name) {
						"isSuspended" -> isSuspended
						"name" -> name
						"equals" -> args?.getOrNull(0) === proxy
						"hashCode" -> System.identityHashCode(proxy)
						"toString" -> "FakeThreadReference($name)"
						else -> null
					}
			}
		return Proxy.newProxyInstance(
			ThreadReference::class.java.classLoader,
			arrayOf(ThreadReference::class.java),
			handler,
		) as ThreadReference
	}

	@Test
	fun `evaluate returns result when thread is suspended`() =
		runBlocking {
			val thread = fakeThread(isSuspended = true, name = "suspended-thread")

			val result = context.evaluate(thread) { "computed-value" }

			assertThat(result).isEqualTo("computed-value")
		}

	@Test
	fun `evaluate returns null when thread is not suspended`() =
		runBlocking {
			val thread = fakeThread(isSuspended = false, name = "resumed-thread")

			val result = context.evaluate(thread) { "should-never-run" }

			assertThat(result).isNull()
		}

	@Test
	fun `evaluate returns null on InvalidStackFrameException`() =
		runBlocking {
			val thread = fakeThread(isSuspended = true)

			val result =
				context.evaluate<String>(thread) {
					throw InvalidStackFrameException("Thread has been resumed")
				}

			assertThat(result).isNull()
		}

	@Test
	fun `evaluate returns null on IncompatibleThreadStateException`() =
		runBlocking {
			val thread = fakeThread(isSuspended = true)

			val result =
				context.evaluate<String>(thread) {
					throw IncompatibleThreadStateException()
				}

			assertThat(result).isNull()
		}

	@Test
	fun `evaluate returns null on ObjectCollectedException`() =
		runBlocking {
			val thread = fakeThread(isSuspended = true)

			val result =
				context.evaluate<String>(thread) {
					throw ObjectCollectedException()
				}

			assertThat(result).isNull()
		}

	@Test
	fun `evaluate returns null on VMDisconnectedException`() =
		runBlocking {
			val thread = fakeThread(isSuspended = true)

			val result =
				context.evaluate<String>(thread) {
					throw VMDisconnectedException()
				}

			assertThat(result).isNull()
		}

	@Test
	fun `cancelPendingForThread drops queued requests with null`() =
		runBlocking {
			val thread = fakeThread(isSuspended = true, name = "cancel-thread")

			val deferred =
				async(Dispatchers.Unconfined) {
					context.evaluate(thread) {
						"should-be-dropped"
					}
				}

			context.cancelPendingForThread(thread)

			assertThat(deferred.await()).isNull()
		}

	@Test
	fun `cancelAllPending drops queued requests across threads with null`() =
		runBlocking {
			val thread1 = fakeThread(isSuspended = true, name = "thread-1")
			val thread2 = fakeThread(isSuspended = true, name = "thread-2")

			val first =
				async(Dispatchers.Unconfined) {
					context.evaluate(thread1) {
						"thread-1-val"
					}
				}
			val second =
				async(Dispatchers.Unconfined) {
					context.evaluate(thread2) {
						"thread-2-val"
					}
				}

			context.cancelAllPending()

			assertThat(first.await()).isNull()
			assertThat(second.await()).isNull()
		}

	@Test
	fun `cancelPendingForThread does not strand batches of other threads`() =
		runBlocking {
			val other = fakeThread(isSuspended = true, name = "other-thread")
			val stepped = fakeThread(isSuspended = true, name = "stepped-thread")

			val pending =
				async(Dispatchers.Unconfined) {
					context.evaluate(other) { "other-val" }
				}

			// Lands in the channel during the batching delay for `other`.
			context.cancelPendingForThread(stepped)

			val result = withTimeout(2_000) { pending.await() }
			assertThat(result).isEqualTo("other-val")
		}

	@Test
	fun `subsequent evaluations in a batch return null when thread resumes during batch`() =
		runBlocking {
			var isSuspended = true
			val handler =
				object : InvocationHandler {
					override fun invoke(
						proxy: Any,
						method: Method,
						args: Array<out Any>?,
					): Any? =
						when (method.name) {
							"isSuspended" -> isSuspended
							"name" -> "dynamic-thread"
							"equals" -> args?.getOrNull(0) === proxy
							"hashCode" -> System.identityHashCode(proxy)
							"toString" -> "FakeThreadReference(dynamic-thread)"
							else -> null
						}
				}
			val thread =
				Proxy.newProxyInstance(
					ThreadReference::class.java.classLoader,
					arrayOf(ThreadReference::class.java),
					handler,
				) as ThreadReference

			val first =
				async {
					context.evaluate(thread) {
						isSuspended = false
						"first"
					}
				}
			val second =
				async {
					context.evaluate(thread) {
						"second"
					}
				}

			assertThat(first.await()).isEqualTo("first")
			assertThat(second.await()).isNull()
		}
}
