package com.itsaky.androidide.agent.repository

import com.google.genai.types.FunctionCall
import com.google.genai.types.FunctionResponse
import com.google.genai.types.Part
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.jvm.optionals.getOrNull

/**
 * The "doer" of the agent. It executes tool calls by directly accessing services.
 */
class Executor {

    companion object {
        private val log = LoggerFactory.getLogger(Executor::class.java)
    }

    suspend fun execute(
        functionCalls: List<FunctionCall>
    ): List<Part> {
        log.info("Executor: Executing ${functionCalls.size} tool call(s)...")

        val out = ArrayList<Part>(functionCalls.size)
        for (call in functionCalls) {
            currentCoroutineContext().ensureActive()

            val toolName = call.name().getOrNull() ?: ""
            val toolResult = dispatchToolCall(call)
            log.info("Executor: Resulting ${toolResult.toResultMap()}")

            out.add(
                Part.builder().functionResponse(
                    FunctionResponse.builder()
                    .name(toolName)
                    .response(toolResult.toResultMap())
                    .build()
                ).build()
            )
        }
        return out
    }

    private suspend fun dispatchToolCall(functionCall: FunctionCall): ToolResult {
        val name =
            functionCall.name().getOrNull() ?: return ToolResult.failure("Unnamed function call")
        val args = functionCall.args().getOrNull() ?: emptyMap()
        log.debug("Dispatching tool call: '$name' with args: $args")

        return when (name) {
            "create_file" -> {
                val path = args["path"] as? String ?: ""
                val content = args["content"] as? String ?: ""
                IDEApiFacade.createFile(path, content)
            }

            "read_file" -> {
                val path = args["path"] as? String ?: ""
                IDEApiFacade.readFile(path)
            }

            "update_file" -> {
                val path = args["path"] as? String ?: ""
                val content = args["content"] as? String ?: ""
                IDEApiFacade.updateFile(path, content)
            }

            "delete_file" -> {
                val path = args["path"] as? String ?: ""
                IDEApiFacade.deleteFile(path)
            }

            "list_files" -> {
                val rawPath = args["path"] as? String
                val path = when {
                    rawPath.isNullOrBlank() -> "."
                    rawPath.startsWith("/home/") -> "."
                    else -> rawPath
                }
                val recursive = args["recursive"]?.toString()?.toBoolean() ?: false
                return try {
                    val baseDir = IProjectManager.getInstance().projectDir.canonicalFile
                    val targetDir = when (path.trim()) {
                        "", ".", "./" -> baseDir
                        else -> File(baseDir, path).canonicalFile
                    }
                    val basePath = baseDir.path
                    val targetPath = targetDir.path
                    val isInside =
                        targetPath == basePath || targetPath.startsWith(basePath + File.separator)
                    if (!isInside) {
                        ToolResult.failure("Access denied: path outside project directory")
                    } else {
                        val relativePath =
                            if (targetPath == basePath) "." else targetDir.relativeTo(baseDir).path
                        log.debug(
                            "Executor: list_files normalized path='{}', recursive={}",
                            relativePath,
                            recursive
                        )
                        IDEApiFacade.listFiles(relativePath, recursive)
                    }
                } catch (e: Exception) {
                    ToolResult.failure("Failed to list files.", e.message)
                }
            }

            "add_dependency" -> {
                val dependency =
                    (args["dependency_string"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (args["dependency"] as? String)
                val buildFilePath = args["build_file_path"] as? String
                if (dependency.isNullOrEmpty() || buildFilePath.isNullOrEmpty()) {
                    ToolResult.failure(
                        "Both 'dependency_string' and 'build_file_path' are required."
                    )
                } else {
                    val dependencyString = if (buildFilePath.endsWith(".kts")) {
                        "implementation(\"$dependency\")"
                    } else {
                        "implementation '$dependency'"
                    }
                    IDEApiFacade.addDependency(dependencyString, buildFilePath)
                }
            }

            "add_string_resource" -> {
                val resourceName = args["name"] as? String
                val resourceValue = args["value"] as? String
                if (resourceName.isNullOrEmpty() || resourceValue.isNullOrEmpty()) {
                    ToolResult.failure("Both 'name' and 'value' are required.")
                } else {
                    IDEApiFacade.addStringResource(resourceName, resourceValue)
                }
            }

            "run_app" -> IDEApiFacade.runApp()
            "trigger_gradle_sync" -> IDEApiFacade.triggerGradleSync()
            "get_build_output" -> IDEApiFacade.getBuildOutput()

            "read_multiple_files" -> {
                val paths = args["paths"] as? List<String> ?: emptyList()
                if (paths.isEmpty()) {
                    ToolResult.failure("The 'paths' parameter cannot be empty.")
                } else {
                    readMultipleFiles(paths)
                }
            }

            else -> {
                log.error("Executor: Unknown function requested: $name")
                ToolResult.failure("Unknown function '$name'")
            }
        }
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
        val dataJson = Json.encodeToString(results)

        return if (allSuccessful) {
            ToolResult.success("Successfully read ${paths.size} files.", dataJson)
        } else {
            ToolResult.failure("One or more files could not be read.", dataJson)
        }
    }
}
