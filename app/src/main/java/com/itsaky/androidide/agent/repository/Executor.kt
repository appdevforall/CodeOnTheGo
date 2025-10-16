package com.itsaky.androidide.agent.repository

import com.google.genai.types.FunctionCall
import com.google.genai.types.FunctionResponse
import com.google.genai.types.Part
import com.itsaky.androidide.agent.model.AddDependencyArgs
import com.itsaky.androidide.agent.model.AddStringResourceArgs
import com.itsaky.androidide.agent.model.CreateFileArgs
import com.itsaky.androidide.agent.model.DeleteFileArgs
import com.itsaky.androidide.agent.model.ListFilesArgs
import com.itsaky.androidide.agent.model.ReadFileArgs
import com.itsaky.androidide.agent.model.ReadMultipleFilesArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.model.UpdateFileArgs
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.jvm.optionals.getOrNull

/**
 * The "doer" of the agent. It executes tool calls by directly accessing services.
 */
class Executor {

    companion object {
        private val log = LoggerFactory.getLogger(Executor::class.java)

        // Tools that only read IDE state and can be executed in parallel safely.
        private val parallelSafeTools = setOf(
            "read_file",
            "list_files",
            "read_multiple_files",
            "get_build_output"
        )

        private val json = Json {
            ignoreUnknownKeys = true
        }
    }

    suspend fun execute(
        functionCalls: List<FunctionCall>
    ): List<Part> = coroutineScope {
        log.info("Executor: Executing ${functionCalls.size} tool call(s)...")

        val (parallelCalls, sequentialCalls) = functionCalls.partition { call ->
            parallelSafeTools.contains(call.name().getOrNull())
        }

        val parallelResults = parallelCalls.map { call ->
            async {
                val toolName = call.name().getOrNull() ?: ""
                val toolResult = dispatchToolCall(call)
                log.info("Executor (Parallel): Resulting ${toolResult.toResultMap()}")

                Part.builder().functionResponse(
                    FunctionResponse.builder()
                        .name(toolName)
                        .response(toolResult.toResultMap())
                        .build()
                ).build()
            }
        }

        val sequentialResults = mutableListOf<Part>()
        for (call in sequentialCalls) {
            val toolName = call.name().getOrNull() ?: ""
            val toolResult = dispatchToolCall(call)
            log.info("Executor (Sequential): Resulting ${toolResult.toResultMap()}")

            val part = Part.builder().functionResponse(
                FunctionResponse.builder()
                    .name(toolName)
                    .response(toolResult.toResultMap())
                    .build()
            ).build()
            sequentialResults.add(part)
        }

        sequentialResults + parallelResults.awaitAll()
    }

    private suspend fun dispatchToolCall(functionCall: FunctionCall): ToolResult {
        val name =
            functionCall.name().getOrNull() ?: return ToolResult.failure("Unnamed function call")
        val args = functionCall.args().getOrNull() as? Map<String, Any?> ?: emptyMap()
        log.debug("Dispatching tool call: '$name' with args: $args")

        return when (name) {
            "create_file" -> {
                val toolArgs = decodeArgs<CreateFileArgs>(args)
                IDEApiFacade.createFile(toolArgs.path, toolArgs.content)
            }

            "read_file" -> {
                val toolArgs = decodeArgs<ReadFileArgs>(args)
                IDEApiFacade.readFile(toolArgs.path)
            }

            "update_file" -> {
                val toolArgs = decodeArgs<UpdateFileArgs>(args)
                IDEApiFacade.updateFile(toolArgs.path, toolArgs.content)
            }

            "delete_file" -> {
                val toolArgs = decodeArgs<DeleteFileArgs>(args)
                IDEApiFacade.deleteFile(toolArgs.path)
            }

            "list_files" -> {
                val toolArgs = decodeArgs<ListFilesArgs>(args)
                IDEApiFacade.listFiles(toolArgs.path, toolArgs.recursive)
            }

            "add_dependency" -> {
                val toolArgs = decodeArgs<AddDependencyArgs>(args)
                if (toolArgs.dependency.isEmpty() || toolArgs.buildFilePath.isEmpty()) {
                    ToolResult.failure("Both 'dependency' and 'build_file_path' are required.")
                } else {
                    val dependencyString = if (toolArgs.buildFilePath.endsWith(".kts")) {
                        "implementation(\"${toolArgs.dependency}\")"
                    } else {
                        "implementation '${toolArgs.dependency}'"
                    }
                    IDEApiFacade.addDependency(dependencyString, toolArgs.buildFilePath)
                }
            }

            "add_string_resource" -> {
                val toolArgs = decodeArgs<AddStringResourceArgs>(args)
                if (toolArgs.name.isEmpty() || toolArgs.value.isEmpty()) {
                    ToolResult.failure("Both 'name' and 'value' are required.")
                } else {
                    IDEApiFacade.addStringResource(toolArgs.name, toolArgs.value)
                }
            }

            "run_app" -> IDEApiFacade.runApp()
            "trigger_gradle_sync" -> IDEApiFacade.triggerGradleSync()
            "get_build_output" -> IDEApiFacade.getBuildOutput()

            "read_multiple_files" -> {
                val toolArgs = decodeArgs<ReadMultipleFilesArgs>(args)
                if (toolArgs.paths.isEmpty()) {
                    ToolResult.failure("The 'paths' parameter cannot be empty.")
                } else {
                    readMultipleFiles(toolArgs.paths)
                }
            }

            else -> {
                log.error("Executor: Unknown function requested: $name")
                ToolResult.failure("Unknown function '$name'")
            }
        }
    }

    private inline fun <reified T> decodeArgs(args: Map<String, Any?>): T {
        val jsonElement = toJsonElement(args)
        return json.decodeFromJsonElement(jsonElement)
    }

    private fun toJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> {
                val content = mutableMapOf<String, JsonElement>()
                value.forEach { (k, v) ->
                    if (k != null) {
                        content[k.toString()] = toJsonElement(v)
                    }
                }
                JsonObject(content)
            }

            is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
            is Array<*> -> JsonArray(value.map { toJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }

    private fun readMultipleFiles(paths: List<String>): ToolResult {
        val results = mutableMapOf<String, String>()
        var allSuccessful = true

        for (path in paths) {
            try {
                val file = File(IProjectManager.getInstance().projectDir, path)
                if (file.exists() && file.isFile) {
                    results[path] = file.readText()
                } else {
                    results[path] = "ERROR: File not found or is a directory."
                    allSuccessful = false
                }
            } catch (e: Exception) {
                results[path] = "ERROR: Could not read file - ${e.message}"
                allSuccessful = false
            }
        }

        // Use kotlinx.serialization to convert the map to a JSON string
        val dataJson = json.encodeToString(results)

        return if (allSuccessful) {
            ToolResult.success("Successfully read ${paths.size} files.", dataJson)
        } else {
            ToolResult.failure("One or more files could not be read.", dataJson)
        }
    }
}
