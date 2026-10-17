package com.itsaky.androidide.lsp.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.PerformCodeActionParams
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.ShowDocumentParams
import com.itsaky.androidide.lsp.models.ShowDocumentResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.api.Workspace
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
