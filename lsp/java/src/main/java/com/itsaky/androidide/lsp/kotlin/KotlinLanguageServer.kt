package com.itsaky.androidide.lsp.kotlin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.java.JavaCompilerProvider
import com.itsaky.androidide.lsp.java.utils.CancelChecker.Companion.isCancelled
import com.itsaky.androidide.lsp.models.CodeFormatResult
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.FailureType
import com.itsaky.androidide.lsp.models.FormatCodeParams
import com.itsaky.androidide.lsp.models.LSPFailure
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.itsaky.androidide.projects.api.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class KotlinLanguageServer : ILanguageServer {

    override var client: ILanguageClient? = null
        private set

    private var _settings: IServerSettings? = null
    private var selectedFile: Path? = null
    private var process: Process? = null
    private val jsonMapper = jacksonObjectMapper()
    private var requestId = 1
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<String>>()


    override val serverId: String = SERVER_ID

    companion object {
        const val SERVER_ID = "ide.lsp.kotlin"
        private val log = LoggerFactory.getLogger(KotlinLanguageServer::class.java)
        private const val KOTLIN_LANGUAGE_SERVER_FILE_NAME = "kotlin-language-server"

    }

    init {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        startLanguageServer()
    }

    override fun shutdown() {
        process?.destroy()
        EventBus.getDefault().unregister(this)
    }

    override fun connectClient(client: ILanguageClient?) {
        this.client = client
    }

    override fun applySettings(settings: IServerSettings?) {
        this._settings = settings
    }

    override fun setupWithProject(project: Project) {
        startLanguageServer()
    }

    private fun handleLspResponse(response: String) {
        try {
            if (!response.trim().startsWith("{")) {
                log.info("Non-JSON LSP Response: $response")
                return
            }

            val jsonNode = jsonMapper.readTree(response)

            if (jsonNode.has("id") && jsonNode.has("result")) {
                val id = jsonNode["id"].asInt()
                val result = jsonNode["result"].toString()

                pendingRequests.remove(id)?.complete(result)
                    ?: log.warn("Received unknown response ID: $id")
            } else if (jsonNode.has("error")) {
                log.error("LSP Error: ${jsonNode["error"]}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendMessage(method: String, params: Any): CompletableDeferred<String> {
        val messageId = requestId++
        val deferred = CompletableDeferred<String>()
        pendingRequests[messageId] = deferred

        val message = mapOf(
            "jsonrpc" to "2.0",
            "id" to messageId,
            "method" to method,
            "params" to params
        )

        val jsonMessage = jsonMapper.writeValueAsString(message)
        process?.outputStream?.let {
            it.write(jsonMessage.toByteArray())
            it.write("\n".toByteArray())
            it.flush()
        }

        return deferred
    }

    private fun startLanguageServer() {
        try {
            jsonMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            val tempDir = Files.createTempDirectory("kotlin-lsp").toFile()
            val extractedLspFile = File(tempDir, KOTLIN_LANGUAGE_SERVER_FILE_NAME)
            val extractedLibDir = File(tempDir, "lib")

            if (!extractedLspFile.exists()) {
                val inputStream =
                    this::class.java.classLoader?.getResourceAsStream("assets/$KOTLIN_LANGUAGE_SERVER_FILE_NAME")
                        ?: throw RuntimeException("LSP asset not found in APK!")

                Files.copy(
                    inputStream,
                    extractedLspFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                inputStream.close()
            }

            extractedLspFile.setExecutable(true)

            if (!extractedLibDir.exists()) extractedLibDir.mkdirs()

            val jarFiles = listOf(
                "annotations-13.0.jar",
                "checker-qual-3.43.0.jar",
                "error_prone_annotations-2.28.0.jar",
                "exposed-core-0.37.3.jar",
                "exposed-dao-0.37.3.jar",
                "exposed-jdbc-0.37.3.jar",
                "failureaccess-1.0.2.jar",
                "fernflower-1.0.jar",
                "google-java-format-1.8.jar",
                "gson-2.10.1.jar",
                "guava-33.3.0-jre.jar",
                "h2-1.4.200.jar",
                "jcommander-1.78.jar",
                "jline-3.24.1.jar",
                "jna-4.2.2.jar",
                "jsr305-3.0.2.jar",
                "kotlin-compiler-2.1.0.jar",
                "kotlin-reflect-2.1.0.jar",
                "kotlin-sam-with-receiver-compiler-plugin-2.1.0.jar",
                "kotlin-script-runtime-2.1.0.jar",
                "kotlin-scripting-common-2.1.0.jar",
                "kotlin-scripting-compiler-2.1.0.jar",
                "kotlin-scripting-compiler-impl-2.1.0.jar",
                "kotlin-scripting-jvm-2.1.0.jar",
                "kotlin-scripting-jvm-host-unshaded-2.1.0.jar",
                "kotlin-stdlib-2.1.0.jar",
                "kotlin-stdlib-jdk7-2.1.0.jar",
                "kotlin-stdlib-jdk8-2.1.0.jar",
                "kotlinx-coroutines-core-jvm-1.6.4.jar",
                "ktfmt-b5d31d1.jar",
                "slf4j-simple-1.7.36.jar",
                "listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar",
                "org.eclipse.lsp4j-0.21.2.jar",
                "org.eclipse.lsp4j.jsonrpc-0.21.2.jar",
                "server-1.3.13.jar",
                "shared-1.3.13.jar",
                "slf4j-api-1.7.25.jar",
                "sqlite-jdbc-3.41.2.1.jar",
                "trove4j-1.0.20200330.jar"
            )

            jarFiles.forEach { jarFile ->
                val inputStream =
                    this::class.java.classLoader?.getResourceAsStream("assets/lib/$jarFile")
                        ?: throw RuntimeException("Missing JAR: $jarFile")

                val outputFile = File(extractedLibDir, jarFile)
                Files.copy(inputStream, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                inputStream.close()
            }

            val classpath = extractedLibDir.listFiles()?.joinToString(":") { it.absolutePath }
                ?: throw RuntimeException("No JARs extracted to lib directory!")

            val javaHome = BuildPreferences.javaHome
            if (javaHome.isBlank()) throw RuntimeException("JAVA_HOME is not set in BuildPreferences!")

            val env = mutableMapOf<String, String>()
            env["JAVA_HOME"] = javaHome
            env["APP_HOME"] = extractedLibDir.absolutePath
            env["CLASSPATH"] = classpath

            val processBuilder = ProcessBuilder(extractedLspFile.absolutePath)
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }

            if (extractedLibDir.listFiles().isNullOrEmpty()) {
                throw RuntimeException("No JAR files found in $extractedLibDir")
            }


            process = processBuilder.start()

            Thread { listenToServerOutput() }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun listenToServerOutput() {
        try {
            process?.inputStream?.bufferedReader()?.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        log.debug("LSP Response: $line")
                        handleLspResponse(line)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun complete(params: CompletionParams?): CompletionResult {
        params ?: return CompletionResult.EMPTY
        return try {
            val response = runBlocking { sendMessage("textDocument/completion", params).await() }
            jsonMapper.readValue(response)
        } catch (e: Exception) {
            e.printStackTrace()
            CompletionResult.EMPTY
        }
    }


    override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
        val response = sendMessage("textDocument/references", params).await()
        return jsonMapper.readValue(response)
    }


    override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
        val response = sendMessage("textDocument/definition", params).await()
        return jsonMapper.readValue(response)
    }


    override suspend fun expandSelection(params: ExpandSelectionParams): Range {
        val response = sendMessage("textDocument/selectionRange", params).await()
        return jsonMapper.readValue(response)
    }


    override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
        val response = sendMessage("textDocument/signatureHelp", params).await()
        return jsonMapper.readValue(response)
    }


    override suspend fun analyze(file: Path): DiagnosticResult {
        val response = sendMessage(
            "textDocument/publishDiagnostics",
            mapOf("uri" to file.toUri().toString())
        ).await()
        return jsonMapper.readValue(response)
    }


    override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
        params ?: return CodeFormatResult(false, mutableListOf())
        val response = runBlocking { sendMessage("textDocument/formatting", params).await() }
        return jsonMapper.readValue(response)
    }


    override fun handleFailure(failure: LSPFailure?): Boolean {
        return when (failure!!.type) {
            FailureType.COMPLETION -> {
                if (isCancelled(failure.error)) {
                    return true
                }
                // TODO: Add kotlin compiler
//                JavaCompilerProvider.getInstance().destroy()
                true
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    @Suppress("unused")
    fun onFileOpened(event: DocumentOpenEvent) {
        selectedFile = event.openedFile
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    @Suppress("unused")
    fun onFileClosed(event: DocumentCloseEvent) {
        selectedFile = null
    }
}
