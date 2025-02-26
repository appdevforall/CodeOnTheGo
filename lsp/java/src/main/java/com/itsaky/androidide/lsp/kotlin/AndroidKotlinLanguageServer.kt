import androidx.annotation.RestrictTo
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.internal.model.CachedCompletion
import com.itsaky.androidide.lsp.java.providers.DefinitionProvider
import com.itsaky.androidide.lsp.java.providers.ReferenceProvider
import com.itsaky.androidide.lsp.java.providers.SignatureProvider
import com.itsaky.androidide.lsp.kotlin.KotlinCodeActionsMenu
import com.itsaky.androidide.lsp.kotlin.KotlinCompilerProvider
import com.itsaky.androidide.lsp.kotlin.KotlinCompilerService
import com.itsaky.androidide.lsp.kotlin.KotlinCompletionProvider
import com.itsaky.androidide.lsp.kotlin.KotlinServerSettings
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.lsp.util.LSPEditorActions
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.IProjectManager.Companion.getInstance
import com.itsaky.androidide.projects.api.Project
import com.itsaky.androidide.utils.DocumentUtils
import java.nio.file.Path
import java.util.Objects

class AndroidKotlinLanguageServer : ILanguageServer {

    companion object {
        const val SERVER_ID = "ide.lsp.kotlin"
    }

    private val kotlinCompletionProvider = KotlinCompletionProvider()
    override val serverId: String = SERVER_ID
    override var client: ILanguageClient? = null
        private set

    private var cachedCompletion: CachedCompletion = CachedCompletion.EMPTY

    private var _settings: IServerSettings? = null

    val settings: IServerSettings
        get() {
            return _settings ?: KotlinServerSettings.getInstance()
                .also { _settings = it }
        }

    override fun shutdown() {
    }

    override fun connectClient(client: ILanguageClient?) {
        this.client = client
    }

    override fun applySettings(settings: IServerSettings?) {
        // Not implemented yet
    }

    override fun setupWithProject(project: Project) {
        LSPEditorActions.ensureActionsMenuRegistered(KotlinCodeActionsMenu())
    }

    private fun updateCachedCompletion(cachedCompletion: CachedCompletion) {
        Objects.requireNonNull(cachedCompletion)
        this.cachedCompletion = cachedCompletion
    }

    override fun complete(params: CompletionParams?): CompletionResult {
        if (params == null) {
            return CompletionResult.EMPTY
        }
        val compiler = getCompiler(params.file)

        if (compiler == null) {
            return CompletionResult.EMPTY
        } else {
            kotlinCompletionProvider.reset(
                compiler, settings, cachedCompletion
            ) { cachedCompletion: CachedCompletion ->
                updateCachedCompletion(cachedCompletion)
            }

        }
        return kotlinCompletionProvider.complete(params)
    }


    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun getCompiler(file: Path?): KotlinCompilerService? {
        if (!DocumentUtils.isKotlinFile(file)) {
            return null
        }
        val root = getInstance().rootProject
            ?: return null
        val module = root.findModuleForFile(file!!) ?: return null
        return KotlinCompilerProvider.get(module)
    }

    // Placeholder for other methods
    override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
        return ReferenceProvider(getCompiler(params.file), params.cancelChecker).findReferences(params)
    }

    override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
        return  DefinitionProvider(getCompiler(params.file), settings, params.cancelChecker).findDefinition(params)
    }

    override suspend fun expandSelection(params: ExpandSelectionParams): Range {
       return Range.NONE
    }

    override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
        return SignatureProvider(getCompiler(params.file), params.cancelChecker).signatureHelp(params)
    }

    override suspend fun analyze(file: Path): DiagnosticResult {
       return DiagnosticResult.NO_UPDATE
    }
}