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
package com.itsaky.androidide.tasks

import android.app.ProgressDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.itsaky.androidide.common.R
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

object TaskExecutor {
	private val log = LoggerFactory.getLogger(TaskExecutor::class.java)

	@JvmOverloads
	@JvmStatic
	fun <R> executeAsync(
		callable: Callable<R>,
		callback: Callback<R>? = null,
	): CompletableFuture<R?> {
		return CompletableFuture
			.supplyAsync {
				try {
					return@supplyAsync callable.call()
				} catch (th: Throwable) {
					log.error("An error occurred while executing Callable in background thread.", th)
					return@supplyAsync null
				}
			}.whenComplete { result, _ -> runOnUiThread { callback?.complete(result) } }
	}

	@JvmOverloads
	@JvmStatic
	fun <R> executeAsyncProvideError(
		callable: Callable<R>,
		callback: CallbackWithError<R>? = null,
	): CompletableFuture<R?> {
		return CompletableFuture
			.supplyAsync {
				try {
					return@supplyAsync callable.call()
				} catch (th: Throwable) {
					log.error("An error occurred while executing Callable in background thread.", th)
					throw CompletionException(th)
				}
			}.whenComplete { result, throwable ->
				runOnUiThread { callback?.complete(result, throwable) }
			}
	}

	fun interface Callback<R> {
		fun complete(result: R?)
	}

	fun interface CallbackWithError<R> {
		fun complete(
			result: R?,
			error: Throwable?,
		)
	}
}

fun <R : Any?> executeAsync(callable: () -> R?) {
	executeAsync(callable) {}
}

@JvmOverloads
@Suppress("DEPRECATION")
inline fun <T> Context.executeWithProgress(
	cancellable: Boolean = false,
	block: (ProgressDialog) -> T,
): T {
	val dialog = ProgressDialog(this)
	dialog.setMessage(getString(R.string.please_wait))
	dialog.setCancelable(cancellable)
	dialog.show()
	return block(dialog)
}

fun <R : Any?> executeAsync(
	callable: () -> R?,
	callback: (R?) -> Unit,
): CompletableFuture<R?> = TaskExecutor.executeAsync({ callable() }) { callback(it) }

fun <R : Any?> executeAsyncProvideError(
	callable: () -> R?,
	callback: (R?, Throwable?) -> Unit,
): CompletableFuture<R?> = TaskExecutor.executeAsyncProvideError(callable, callback)

/**
 * Shared [Handler] bound to the main looper. Exposed (rather than creating a new [Handler] per
 * call) so that code posting delayed work and later cancelling it via [Handler.removeCallbacks]
 * goes through the same instance - `removeCallbacks` only removes callbacks posted by the exact
 * same [Handler].
 */
val mainThreadHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

fun runOnUiThread(action: () -> Unit) {
	if (Looper.getMainLooper().isCurrentThread) {
		action()
	} else {
		mainThreadHandler.post(action)
	}
}
