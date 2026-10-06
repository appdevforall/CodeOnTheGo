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
package org.appdevforall.codeonthego.lsp.xml

import androidx.annotation.RestrictTo
import org.appdevforall.codeonthego.eventbus.events.editor.DocumentChangeEvent
import org.appdevforall.codeonthego.lsp.api.ICompletionProvider
import org.appdevforall.codeonthego.lsp.api.ILanguageClient
import org.appdevforall.codeonthego.lsp.api.ILanguageServer
import org.appdevforall.codeonthego.lsp.api.IServerSettings
import org.appdevforall.codeonthego.lsp.models.CodeFormatResult
import org.appdevforall.codeonthego.lsp.models.CompletionParams
import org.appdevforall.codeonthego.lsp.models.CompletionResult
import org.appdevforall.codeonthego.lsp.models.DefinitionParams
import org.appdevforall.codeonthego.lsp.models.DefinitionResult
import org.appdevforall.codeonthego.lsp.models.DiagnosticResult
import org.appdevforall.codeonthego.lsp.models.ExpandSelectionParams
import org.appdevforall.codeonthego.lsp.models.FormatCodeParams
import org.appdevforall.codeonthego.lsp.models.LSPFailure
import org.appdevforall.codeonthego.lsp.models.ReferenceParams
import org.appdevforall.codeonthego.lsp.models.ReferenceResult
import org.appdevforall.codeonthego.lsp.models.SignatureHelp
import org.appdevforall.codeonthego.lsp.models.SignatureHelpParams
import org.appdevforall.codeonthego.lsp.util.NoCompletionsProvider
import org.appdevforall.codeonthego.lsp.xml.models.XMLServerSettings
import org.appdevforall.codeonthego.lsp.xml.providers.AdvancedEditProvider.onContentChange
import org.appdevforall.codeonthego.lsp.xml.providers.CodeFormatProvider
import org.appdevforall.codeonthego.lsp.xml.providers.XmlCompletionProvider
import org.appdevforall.codeonthego.models.Range
import org.appdevforall.codeonthego.projects.api.Workspace
import org.appdevforall.codeonthego.utils.DocumentUtils
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.nio.file.Path

/**
 * Language server implementation for XML files.
 *
 * @author Akash Yadav
 */
class XMLLanguageServer : ILanguageServer {
	@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
	override var client: ILanguageClient? = null
		private set

	private var settings: IServerSettings? = null

	override val serverId: String = SERVER_ID

	init {
		EventBus.getDefault().register(this)
	}

	override fun shutdown() {
		if (EventBus.getDefault().isRegistered(this)) {
			EventBus.getDefault().unregister(this)
		}
	}

	override fun connectClient(client: ILanguageClient?) {
		this.client = client
	}

	override fun applySettings(settings: IServerSettings?) {
		this.settings = settings
	}

	override fun setupWithProject(workspace: Workspace) {}

	override fun complete(params: CompletionParams?): CompletionResult {
		val completionProvider: ICompletionProvider
		completionProvider =
			if (!getSettings().completionsEnabled()) {
				NoCompletionsProvider()
			} else {
				XmlCompletionProvider(getSettings())
			}
		return completionProvider.complete(params)
	}

	fun getSettings(): IServerSettings {
		if (settings == null) {
			settings = XMLServerSettings
		}
		return settings!!
	}

	override suspend fun findReferences(params: ReferenceParams): ReferenceResult = ReferenceResult(emptyList())

	override suspend fun findDefinition(params: DefinitionParams): DefinitionResult = DefinitionResult(emptyList())

	override suspend fun expandSelection(params: ExpandSelectionParams): Range = params.selection

	override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp = SignatureHelp(emptyList(), -1, -1)

	override suspend fun analyze(file: Path): DiagnosticResult = DiagnosticResult.NO_UPDATE

	override fun formatCode(params: FormatCodeParams?): CodeFormatResult = CodeFormatProvider().format(params)

	@Subscribe(threadMode = ThreadMode.BACKGROUND)
	fun onDocumentChange(event: DocumentChangeEvent) {
		if (!DocumentUtils.isXmlFile(event.changedFile)) {
			return
		}
		onContentChange(event)
	}

	override fun handleFailure(failure: LSPFailure?): Boolean = super<ILanguageServer>.handleFailure(failure)

	companion object {
		const val SERVER_ID = "ide.lsp.xml"
	}
}
