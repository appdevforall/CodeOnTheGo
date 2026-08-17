package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The bracket is what decides whether the toolbar shows "Run" or "Cancel build": while it is
 * held, `GradleBuildService.isUserVisibleBuildInProgress` is false and the editor's build
 * listener is suppressed, so the completion callback that clears "a build is running" never
 * arrives. A release that any path can skip therefore leaves the button relabelled for the rest
 * of the process - the defect these tests pin.
 */
class InternalBuildBracketTest {
	@Test
	fun `the bracket is held for the duration of the work and released after it`() =
		runTest {
			val bracket = InternalBuildBracket()

			val heldDuringWork = bracket.hold { bracket.isHeld }

			assertThat(heldDuringWork).isTrue()
			assertThat(bracket.isHeld).isFalse()
		}

	@Test
	fun `work that throws still releases the bracket, and the throw propagates`() =
		runTest {
			val bracket = InternalBuildBracket()

			val thrown =
				runCatching {
					bracket.hold<Unit> { throw IllegalStateException("proxy app build blew up") }
				}.exceptionOrNull()

			assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
			assertThat(bracket.isHeld).isFalse()
		}

	@Test
	fun `work that is cancelled still releases the bracket`() =
		runTest {
			val bracket = InternalBuildBracket()
			val started = CompletableDeferred<Unit>()

			val job =
				launch {
					bracket.hold {
						started.complete(Unit)
						awaitCancellation()
					}
				}
			started.await()
			assertThat(bracket.isHeld).isTrue()

			job.cancelAndJoin()

			assertThat(bracket.isHeld).isFalse()
		}

	@Test
	fun `the editor listener comes back after the work throws`() =
		runTest {
			val bracket = InternalBuildBracket()
			val listener = "the editor's build listener"

			runCatching { bracket.hold<Unit> { throw IllegalStateException("boom") } }

			assertThat(bracket.suppressWhileHeld(listener)).isEqualTo(listener)
		}

	@Test
	fun `the editor listener is suppressed while the work runs`() =
		runTest {
			val bracket = InternalBuildBracket()
			val listener = "the editor's build listener"

			val duringWork = bracket.hold { bracket.suppressWhileHeld(listener) }

			assertThat(duringWork).isNull()
		}

	@Test
	fun `a nested release does not un-hold the outer bracket`() =
		runTest {
			val bracket = InternalBuildBracket()

			val heldAfterInner =
				bracket.hold {
					bracket.hold { }
					bracket.isHeld
				}

			assertThat(heldAfterInner).isTrue()
			assertThat(bracket.isHeld).isFalse()
		}

	@Test
	fun `the captured output is dropped on the outermost acquire only`() =
		runTest {
			var firstAcquires = 0
			val bracket = InternalBuildBracket(onFirstAcquire = { firstAcquires++ })

			bracket.hold { bracket.hold { } }
			assertThat(firstAcquires).isEqualTo(1)

			// A later, separate internal build is outermost again, so it clears the tail the
			// previous one left unread.
			bracket.hold { }
			assertThat(firstAcquires).isEqualTo(2)
		}

	@Test
	fun `a bracket that was never taken suppresses nothing`() =
		runTest {
			val bracket = InternalBuildBracket()

			assertThat(bracket.isHeld).isFalse()
			assertThat(bracket.suppressWhileHeld("listener")).isEqualTo("listener")
		}

	@Test
	fun `work that returns normally publishes held then not held`() =
		runTest {
			val edges = mutableListOf<Boolean>()
			val bracket = InternalBuildBracket(onHeldChanged = { edges.add(it) })

			val duringWork = bracket.hold { edges.toList() }

			assertThat(duringWork).containsExactly(true)
			assertThat(edges).containsExactly(true, false).inOrder()
		}

	@Test
	fun `work that throws still publishes not held, and the throw propagates`() =
		runTest {
			val edges = mutableListOf<Boolean>()
			val bracket = InternalBuildBracket(onHeldChanged = { edges.add(it) })

			val thrown =
				runCatching {
					bracket.hold<Unit> { throw IllegalStateException("proxy app build blew up") }
				}.exceptionOrNull()

			assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
			assertThat(thrown).hasMessageThat().isEqualTo("proxy app build blew up")
			assertThat(edges).containsExactly(true, false).inOrder()
		}

	@Test
	fun `work that is cancelled still publishes not held`() =
		runTest {
			val edges = mutableListOf<Boolean>()
			val bracket = InternalBuildBracket(onHeldChanged = { edges.add(it) })
			val started = CompletableDeferred<Unit>()

			val job =
				launch {
					bracket.hold {
						started.complete(Unit)
						awaitCancellation()
					}
				}
			started.await()
			assertThat(edges).containsExactly(true)

			job.cancelAndJoin()

			assertThat(edges).containsExactly(true, false).inOrder()
		}

	@Test
	fun `a nested internal build publishes only the outermost transitions`() =
		runTest {
			val edges = mutableListOf<Boolean>()
			val bracket = InternalBuildBracket(onHeldChanged = { edges.add(it) })

			val afterInner =
				bracket.hold {
					bracket.hold { }
					edges.toList()
				}

			assertThat(afterInner).containsExactly(true)
			assertThat(edges).containsExactly(true, false).inOrder()
		}

	@Test
	fun `a listener that throws on acquire leaves the depth and the result intact`() =
		runTest {
			val bracket = InternalBuildBracket(onHeldChanged = { throw IllegalStateException("bad observer") })

			val result = bracket.hold { "built" }

			assertThat(result).isEqualTo("built")
			assertThat(bracket.isHeld).isFalse()
		}

	@Test
	fun `a listener that throws does not mask the work's own exception`() =
		runTest {
			val bracket = InternalBuildBracket(onHeldChanged = { throw IllegalStateException("bad observer") })

			val thrown =
				runCatching {
					bracket.hold<Unit> { throw IllegalArgumentException("proxy app build blew up") }
				}.exceptionOrNull()

			assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
			assertThat(bracket.isHeld).isFalse()
		}

	@Test
	fun `a throwing listener does not stop a later internal build being published`() =
		runTest {
			var calls = 0
			val bracket =
				InternalBuildBracket(
					onHeldChanged = {
						calls++
						throw IllegalStateException("bad observer")
					},
				)

			bracket.hold { }
			bracket.hold { }

			assertThat(calls).isEqualTo(4)
			assertThat(bracket.isHeld).isFalse()
		}
}
