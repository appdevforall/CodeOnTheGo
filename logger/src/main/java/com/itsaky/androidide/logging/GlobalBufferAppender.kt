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

package com.itsaky.androidide.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.Context
import com.itsaky.androidide.logging.encoder.IDELogFormatLayout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global buffer appender that captures all SLF4J logs and makes them available
 * to the IDE Logs tab when it's created.
 *
 * @author Akash Yadav
 */
class GlobalBufferAppender : AppenderBase<ILoggingEvent>() {

  private val logLayout = IDELogFormatLayout(false)

  companion object {
    private val buffer = ConcurrentLinkedQueue<String>()
    private val maxBufferSize = 1000 // Keep last 1000 log entries
    private val bufferSize = AtomicInteger(0)
    private val consumers = mutableSetOf<((String) -> Unit)>()
    
    /**
     * Register a consumer to receive log messages.
     * The consumer will receive both new messages and all buffered messages.
     */
    fun registerConsumer(consumer: (String) -> Unit) {
      consumers.add(consumer)
      // Send all buffered messages to the new consumer
      buffer.forEach { message ->
        consumer(message)
      }
    }
    
    /**
     * Unregister a consumer.
     */
    fun unregisterConsumer(consumer: (String) -> Unit) {
      consumers.remove(consumer)
    }
  }

  override fun start() {
    this.logLayout.start()
    super.start()
  }

  override fun stop() {
    super.stop()
    this.logLayout.stop()
  }

  override fun setContext(context: Context?) {
    super.setContext(context)
    this.logLayout.context = context
  }

  override fun append(eventObject: ILoggingEvent?) {
    if (eventObject == null || !isStarted) {
      return
    }

    // Format the log message
    val formattedMessage = logLayout.doLayout(eventObject).trim()
    
    // Add to buffer
    buffer.offer(formattedMessage)
    
    // Maintain buffer size
    if (bufferSize.incrementAndGet() > maxBufferSize) {
      buffer.poll() // Remove oldest entry
      bufferSize.decrementAndGet()
    }
    
    // Send to all registered consumers
    consumers.forEach { consumer ->
      try {
        consumer(formattedMessage)
      } catch (e: Exception) {
        // Ignore exceptions from consumers to avoid breaking the logging system
      }
    }
  }
}
