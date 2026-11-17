package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Test
import java.util.concurrent.locks.LockSupport

/**
 * [lockWithin] must never leave a lock held that the caller doesn't know it got. The timeout can
 * fire just after lock() returns, and so can the caller's cancellation; either one used to leak the
 * lock for good, and every later plugin load waited out the timeout and ran without it.
 */
class LockWithinTest {
	@Test
	fun aLockTakenAsTheWaitTimesOutIsReportedHeld() =
		runBlocking {
			var leaked = 0
			repeat(ROUNDS) { round ->
				val mutex = Mutex()
				mutex.lock()
				val waiter = async(Dispatchers.IO) { mutex.lockWithin(1L) }
				// Release around the 1 ms timeout, jittered, so some releases land as it fires.
				val releaser =
					launch(Dispatchers.Default) {
						LockSupport.parkNanos(700_000L + (round % 600) * 1_000L)
						mutex.unlock()
					}
				val locked = waiter.await()
				releaser.join()
				if (locked) {
					mutex.unlock()
				} else if (mutex.isLocked) {
					leaked++
				}
			}
			assertWithMessage("rounds where the lock was taken but reported free").that(leaked).isEqualTo(0)
		}

	// withTimeoutOrNull throws when its caller was cancelled, even after lock() returned, so the
	// caller never sees the flag. Cancelling inside lock() hits that window every time.
	@Test
	fun aCallerCancelledJustAfterTakingTheLockLeavesItFree() =
		runBlocking {
			val mutex = Mutex()
			lateinit var caller: Job
			val cancelsItsTaker =
				object : Mutex by mutex {
					override suspend fun lock(owner: Any?) {
						mutex.lock(owner)
						caller.cancel()
					}
				}
			caller = launch(start = CoroutineStart.LAZY) { cancelsItsTaker.lockWithin(10_000L) }
			caller.join()

			assertWithMessage("caller cancelled").that(caller.isCancelled).isTrue()
			assertWithMessage("lock left held by the cancelled caller").that(mutex.isLocked).isFalse()
		}

	private companion object {
		// The old code leaked in about 1 in 100 timeouts, so 3,000 rounds (about 3 s) catch it.
		const val ROUNDS = 3_000
	}
}
