package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.lsp.api.AbstractServiceProvider
import com.itsaky.androidide.lsp.api.ICompletionProvider
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.internal.model.CachedCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinAnnotationCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinClassCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinConstructorCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinEnumCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinFunctionCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinKeywordCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinMethodCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinModuleCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinPropertyFieldCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinSnippetCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinTypeParameterCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinValueCompletion
import com.itsaky.androidide.lsp.kotlin.completion.KotlinVariableCompletion
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class KotlinCompletionProvider : AbstractServiceProvider(), ICompletionProvider {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(KotlinCompletionProvider::class.java)
        private const val MAX_COMPLETION_ITEMS = CompletionResult.MAX_ITEMS
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val completing = AtomicBoolean(false)
    private lateinit var compiler: KotlinCompilerService
    private var cache: CachedCompletion? = null
    private var nextCacheConsumer: ((CachedCompletion) -> Unit)? = null
    private val functionCompletion = KotlinFunctionCompletion()
    private val variableCompletion = KotlinVariableCompletion()
    private val classCompletion = KotlinClassCompletion()
    private val methodCompletion = KotlinMethodCompletion()
    private val constructorCompletion = KotlinConstructorCompletion()
    private val enumCompletion = KotlinEnumCompletion()
    private val annotationCompletion = KotlinAnnotationCompletion()
    private val moduleCompletion = KotlinModuleCompletion()
    private val typeParameterCompletion = KotlinTypeParameterCompletion()
    private val keywordCompletion = KotlinKeywordCompletion()
    private val propertyFieldCompletion = KotlinPropertyFieldCompletion()
    private val snippetCompletion = KotlinSnippetCompletion()
    private val valueCompletion = KotlinValueCompletion()

    fun reset(
        compiler: KotlinCompilerService,
        settings: IServerSettings,
        cache: CachedCompletion,
        nextCacheConsumer: (CachedCompletion) -> Unit,
    ): KotlinCompletionProvider {
        this.compiler = compiler
        this.cache = cache
        this.nextCacheConsumer = nextCacheConsumer
        applySettings(settings)
        return this
    }

    override fun canComplete(file: Path?): Boolean {
        return super.canComplete(file) && file?.toString()?.endsWith(".kt") == true
    }

    override fun complete(params: CompletionParams): CompletionResult {
        if (completing.get()) {
            log.error("Cannot complete, a completion task is already in progress")
            return CompletionResult.EMPTY
        }

        completing.set(true)
        return runBlocking {
            try {
                val fileContent = params.requireContents().toString()
                val cursorPos = params.position.index

                val completions = coroutineScope.async {
                    val functions = functionCompletion.complete(fileContent, cursorPos)
                    val variables = variableCompletion.complete(fileContent, cursorPos)
                    val classes = classCompletion.complete(fileContent, cursorPos)
                    val methods = methodCompletion.complete(fileContent, cursorPos)
                    val constructors = constructorCompletion.complete(fileContent, cursorPos)
                    val enums = enumCompletion.complete(fileContent, cursorPos)
                    val annotations = annotationCompletion.complete(fileContent, cursorPos)
                    val modules = moduleCompletion.complete(fileContent, cursorPos)
                    val types = typeParameterCompletion.complete(fileContent, cursorPos)
                    val keywords = keywordCompletion.complete(fileContent, cursorPos)
                    val propertyFields = propertyFieldCompletion.complete(fileContent, cursorPos)
                    val snippets = snippetCompletion.complete(fileContent, cursorPos)
                    val values = valueCompletion.complete(fileContent, cursorPos)

                    functions + variables + classes + methods + constructors +
                        enums + annotations + modules + types + keywords + propertyFields + snippets + values
                }.await()

                CompletionResult(completions)
            } catch (err: Throwable) {
                log.error("An error occurred while computing completions", err)
                CompletionResult.EMPTY
            } finally {
                completing.set(false)
            }
        }
    }
}
