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

package org.appdevforall.codeonthego.handlers

import org.appdevforall.codeonthego.lsp.PluginLanguageSupport
import org.appdevforall.codeonthego.lsp.api.ILanguageClient
import org.appdevforall.codeonthego.lsp.api.ILanguageServerRegistry
import org.appdevforall.codeonthego.lsp.debug.IDebugClient
import org.appdevforall.codeonthego.lsp.java.JavaLanguageServer
import org.appdevforall.codeonthego.lsp.kotlin.KotlinLanguageServer
import org.appdevforall.codeonthego.lsp.xml.XMLLanguageServer
import org.appdevforall.codeonthego.utils.FeatureFlags

/**
 *
 * @author Akash Yadav
 */
object LspHandler {
	fun registerLanguageServers() {
		ILanguageServerRegistry.default.apply {
			getServer(JavaLanguageServer.SERVER_ID) ?: register(JavaLanguageServer())
			getServer(KotlinLanguageServer.SERVER_ID) ?: register(KotlinLanguageServer())
			getServer(XMLLanguageServer.SERVER_ID) ?: register(XMLLanguageServer())
		}
		PluginLanguageSupport.registerServers()
	}

	fun connectClient(client: ILanguageClient) {
		ILanguageServerRegistry.default.connectClient(client)
	}

	@Throws(Throwable::class)
	suspend fun connectDebugClient(client: IDebugClient) = ILanguageServerRegistry.default.connectDebugClient(client)

	fun destroyLanguageServers(isConfigurationChange: Boolean) {
		if (isConfigurationChange) {
			return
		}
		ILanguageServerRegistry.default.destroy()
	}
}
