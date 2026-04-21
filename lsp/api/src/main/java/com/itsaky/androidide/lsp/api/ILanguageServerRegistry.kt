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
package com.itsaky.androidide.lsp.api

import com.itsaky.androidide.lsp.debug.DebugClientConnectionResult
import com.itsaky.androidide.lsp.debug.IDebugClient

/**
 * A language server registry which keeps track of registered language servers.
 * 
 * @author Akash Yadav
 */
abstract class ILanguageServerRegistry {
	/**
	 * Register the language server.
	 * 
	 * @param server The server to register.
	 */
	abstract fun register(server: ILanguageServer)

	/**
	 * Connects client to all the registered [ILanguageServer]s.
	 */
	abstract fun connectClient(client: ILanguageClient)

	/**
	 * Connects debug client to all the registered [ILanguageServer]s.
	 * 
	 * @param client The debug client to register.
	 * @return A map of server IDs to their corresponding [DebugClientConnectionResult].
	 */
	@Throws(Throwable::class)
	abstract suspend fun connectDebugClient(client: IDebugClient): Map<String, DebugClientConnectionResult>

	/**
	 * Unregister the given server. If any server is registered with the given server ID, a shutdown
	 * request will be sent to that server.
	 * 
	 * @param serverId The ID of the server to unregister.
	 */
	abstract fun unregister(serverId: String)

	/**
	 * Calls [.unregister] for all the registered language servers.
	 */
	abstract fun destroy()

	/**
	 * Get the [ILanguageServer] registered with the given server ID.
	 * 
	 * @param serverId The ID of the language server.
	 * @return The [ILanguageServer] instance. Or `null` if no server is registered
	 * with the provided ID.
	 */
	abstract fun getServer(serverId: String): ILanguageServer?

	companion object {

		/**
		 * The default implementation of [ILanguageServerRegistry].
		 */
		val default: ILanguageServerRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
			DefaultLanguageServerRegistry()
		}
	}
}
