package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.plugins.PluginLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Plugin loads run one at a time, and install swaps a plugin's file between them: two overlapping
 * loads could each load the same plugin, and a load under way could load a file install replaced.
 * The wait is bounded, so a load stuck in a plugin's code can't block every later one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PluginManagerLoadLockTest {
	@Test
	fun loadWaitsWhileInstallSwapsAFile() =
		runBlocking {
			val manager = PluginManager.getInstance(RuntimeEnvironment.getApplication(), Any(), SilentLogger)
			val swapping = CompletableDeferred<Unit>()
			val swapDone = CompletableDeferred<Unit>()
			val swap =
				launch(Dispatchers.Default) {
					manager.whileNotLoading {
						swapping.complete(Unit)
						swapDone.await()
					}
				}
			swapping.await()

			val load = async(Dispatchers.Default) { manager.loadPlugins() }
			// With no plugin files, an unguarded load finishes in milliseconds.
			delay(500)
			assertWithMessage("loadPlugins() ran while a file swap held the lock").that(load.isCompleted).isFalse()

			swapDone.complete(Unit)
			withTimeout(10_000) {
				swap.join()
				load.await()
			}
		}

	@Test
	fun aLoadStuckHoldingTheLockDoesNotBlockTheNextForever() =
		runBlocking {
			val manager = PluginManager.getInstance(RuntimeEnvironment.getApplication(), Any(), SilentLogger)
			manager.loadLockWaitMs = 300
			val stuck = CompletableDeferred<Unit>()
			val holding = CompletableDeferred<Unit>()
			// Stands in for a load whose plugin never returns from initialize() or activate().
			val holder =
				launch(Dispatchers.Default) {
					manager.whileNotLoading {
						holding.complete(Unit)
						stuck.await()
					}
				}
			holding.await()
			try {
				// Waits out loadLockWaitMs, then loads without the lock instead of waiting for good.
				withTimeout(10_000) { manager.loadPlugins() }
			} finally {
				stuck.complete(Unit)
				holder.join()
				manager.loadLockWaitMs = 30_000L
			}
		}

	private object SilentLogger : PluginLogger {
		override val pluginId = "test"

		override fun debug(message: String) = Unit

		override fun debug(
			message: String,
			error: Throwable,
		) = Unit

		override fun info(message: String) = Unit

		override fun info(
			message: String,
			error: Throwable,
		) = Unit

		override fun warn(message: String) = Unit

		override fun warn(
			message: String,
			error: Throwable,
		) = Unit

		override fun error(message: String) = Unit

		override fun error(
			message: String,
			error: Throwable,
		) = Unit
	}
}
