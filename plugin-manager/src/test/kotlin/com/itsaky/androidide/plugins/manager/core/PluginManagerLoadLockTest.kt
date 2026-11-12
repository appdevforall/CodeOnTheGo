package com.itsaky.androidide.plugins.manager.core

import com.itsaky.androidide.plugins.PluginLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Plugin loads run one at a time, and install swaps a plugin's file between them: two overlapping
 * loads could each load the same plugin, and a load under way could load a file install replaced.
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
			assertFalse("loadPlugins() ran while a file swap held the lock", load.isCompleted)

			swapDone.complete(Unit)
			withTimeout(10_000) {
				swap.join()
				load.await()
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
