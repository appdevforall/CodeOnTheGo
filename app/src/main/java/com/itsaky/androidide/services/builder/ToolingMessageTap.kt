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

package com.itsaky.androidide.services.builder

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import java.io.InputStream

/**
 * Observes what one tooling server process sends the IDE.
 *
 * Only the profileable variant installs a [factory], so debug and release builds carry this
 * interface and nothing behind it.
 */
interface ToolingMessageTap {
	/** Wraps the server's raw output before the JSON-RPC reader consumes it. */
	fun wrapInput(input: InputStream): InputStream

	/** Wraps the JSON-RPC consumer of messages the server sends. */
	fun wrapMessages(consumer: MessageConsumer): MessageConsumer

	/** Called once the server's listener and process have both finished. */
	fun onServerStopped()

	companion object {
		/** Creates a tap per server process, or null to observe nothing. */
		@JvmStatic
		@Volatile
		var factory: (() -> ToolingMessageTap)? = null
	}
}
