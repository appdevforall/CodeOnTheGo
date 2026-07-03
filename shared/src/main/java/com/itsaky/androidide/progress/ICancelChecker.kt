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

package com.itsaky.androidide.progress

import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Check whether a process is cancelled.
 *
 * @author Akash Yadav
 */
interface ICancelChecker {

  /**
   * Cancel this process.
   */
  fun cancel()

  /**
   * Check whether this process has been cancelled or not.
   *
   * @return Whether the process has been cancelled.
   */
  fun isCancelled(): Boolean

  /**
   * Throw [CancellationException] if this process has been cancelled.
   */
  @Throws(CancellationException::class)
  fun abortIfCancelled()

  /**
   * Register [listener] to run when this process is cancelled — a *push* notification, so a consumer
   * can react to cancellation immediately instead of polling [isCancelled]. If already cancelled,
   * [listener] runs synchronously now. [listener] runs at most once.
   *
   * The default implementation only fires when already cancelled; an implementation that can transition
   * to cancelled after registration (e.g. [Default]) overrides this to fire on the transition.
   */
  fun invokeOnCancel(listener: () -> Unit) {
    if (isCancelled()) {
      listener()
    }
  }

  open class Default(cancelled: Boolean = false) : ICancelChecker {

    private val cancelled = AtomicBoolean(cancelled)
    private val onCancelListeners = CopyOnWriteArrayList<() -> Unit>()

    override fun cancel() {
      // Fire listeners once, on the false -> true transition only.
      if (cancelled.compareAndSet(false, true)) {
        onCancelListeners.forEach { it() }
        onCancelListeners.clear()
      }
    }

    override fun isCancelled(): Boolean {
      return cancelled.get()
    }

    override fun abortIfCancelled() {
      if (isCancelled()) {
        throw CancellationException()
      }
    }

    override fun invokeOnCancel(listener: () -> Unit) {
      if (isCancelled()) {
        listener()
        return
      }
      onCancelListeners.add(listener)
      // Guard the race where cancel() ran between the check above and the add: if we now observe
      // cancellation, run the listener ourselves (removing it so cancel() can't also run it).
      if (isCancelled() && onCancelListeners.remove(listener)) {
        listener()
      }
    }
  }

  companion object {

    /**
     * A no-op cancel checker. The task is never cancelled.
     */
    @JvmField
    val NOOP = object : Default(false) {
      // Never transitions to cancelled, so retaining listeners would only leak them.
      override fun invokeOnCancel(listener: () -> Unit) = Unit
    }

    /**
     * An already cancelled cancel checker.
     */
    @JvmField
    val CANCELLED = Default(true)
  }
}
