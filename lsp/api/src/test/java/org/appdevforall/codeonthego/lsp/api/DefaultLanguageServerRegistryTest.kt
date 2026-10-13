package org.appdevforall.codeonthego.lsp.api

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.lsp.models.CompletionParams
import org.appdevforall.codeonthego.lsp.models.CompletionResult
import org.appdevforall.codeonthego.lsp.models.DefinitionParams
import org.appdevforall.codeonthego.lsp.models.DefinitionResult
import org.appdevforall.codeonthego.lsp.models.DiagnosticItem
import org.appdevforall.codeonthego.lsp.models.DiagnosticResult
import org.appdevforall.codeonthego.lsp.models.ExpandSelectionParams
import org.appdevforall.codeonthego.lsp.models.PerformCodeActionParams
import org.appdevforall.codeonthego.lsp.models.ReferenceParams
import org.appdevforall.codeonthego.lsp.models.ReferenceResult
import org.appdevforall.codeonthego.lsp.models.ShowDocumentParams
import org.appdevforall.codeonthego.lsp.models.ShowDocumentResult
import org.appdevforall.codeonthego.lsp.models.SignatureHelp
import org.appdevforall.codeonthego.lsp.models.SignatureHelpParams
import org.appdevforall.codeonthego.models.Location
import org.appdevforall.codeonthego.models.Range
import org.appdevforall.codeonthego.projects.api.Workspace
import org.junit.Test
import java.io.File
import java.nio.file.Path

class DefaultLanguageServerRegistryTest {
	@Test
	fun `a server registered after the client connects receives the client`() {
		val registry = DefaultLanguageServerRegistry()
		val client = NoOpClient()
		registry.connectClient(client)

		val late = RecordingServer("late")
		registry.register(late)

		assertThat(late.client).isSameInstanceAs(client)
		registry.destroy()
	}

	@Test
	fun `a destroyed registry does not hand its old client to new servers`() {
		val registry = DefaultLanguageServerRegistry()
		registry.connectClient(NoOpClient())
		registry.destroy()

		val server = RecordingServer("fresh")
		registry.register(server)

		assertThat(server.client).isNull()
		registry.destroy()
	}

	private class RecordingServer(
		override val serverId: String,
	) : ILanguageServer {
		override var client: ILanguageClient? = null

		override fun shutdown() = Unit

		override fun connectClient(client: ILanguageClient?) {
			this.client = client
		}

		override fun applySettings(settings: IServerSettings?) = Unit

		override fun setupWithProject(workspace: Workspace) = Unit

		override fun complete(params: CompletionParams?): CompletionResult = CompletionResult.EMPTY

		override suspend fun findReferences(params: ReferenceParams): ReferenceResult = ReferenceResult.empty()

		override suspend fun findDefinition(params: DefinitionParams): DefinitionResult = DefinitionResult.empty()

		override suspend fun expandSelection(params: ExpandSelectionParams): Range = params.selection

		override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp = SignatureHelp.empty()

		override suspend fun analyze(file: Path): DiagnosticResult = DiagnosticResult.NO_UPDATE
	}

	private class NoOpClient : ILanguageClient {
		override fun getDiagnosticAt(
			file: File,
			line: Int,
			column: Int,
		): DiagnosticItem? = null

		override fun performCodeAction(params: PerformCodeActionParams) = Unit

		override fun publishDiagnostics(result: DiagnosticResult) = Unit

		override fun showDocument(params: ShowDocumentParams): ShowDocumentResult = ShowDocumentResult(false)

		override fun showLocations(locations: List<Location>) = Unit
	}
}
