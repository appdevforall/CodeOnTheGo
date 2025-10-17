package com.itsaky.androidide.agent.repository

import com.google.genai.types.FunctionCall
import com.google.genai.types.FunctionResponse
import com.google.genai.types.Part
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.tool.ToolRouter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull

/**
 * The "doer" of the agent. It executes tool calls by directly accessing services.
 */
class Executor(
    private val toolRouter: ToolRouter
) {

    companion object {
        private val log = LoggerFactory.getLogger(Executor::class.java)

        // Tools that only read IDE state and can be executed in parallel safely.
        private val parallelSafeTools = setOf(
            "read_file",
            "list_files",
            "read_multiple_files",
            "get_build_output"
        )

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
                executeCall(call, "Parallel")
            }
        }

        val sequentialResults = mutableListOf<Part>()
        for (call in sequentialCalls) {
            sequentialResults.add(executeCall(call, "Sequential"))
        }

        sequentialResults + parallelResults.awaitAll()
    }

    private suspend fun executeCall(
        call: FunctionCall,
        executionMode: String
    ): Part {
        val toolName = call.name().getOrNull()
        val args = call.args().getOrNull() as? Map<String, Any?> ?: emptyMap()

        val result = if (toolName.isNullOrBlank()) {
            log.error("Executor ($executionMode): Encountered unnamed function call.")
            ToolResult.failure("Unnamed function call")
        } else {
            log.debug("Executor ($executionMode): Dispatching '$toolName' with args: $args")
            toolRouter.dispatch(toolName, args)
        }

        log.info("Executor ($executionMode): Resulting ${result.toResultMap()}")
        return buildFunctionResponsePart(toolName.orEmpty(), result)
    }

    private fun buildFunctionResponsePart(
        toolName: String,
        toolResult: ToolResult
    ): Part {
        return Part.builder().functionResponse(
            FunctionResponse.builder()
                .name(toolName)
                .response(toolResult.toResultMap())
                .build()
        ).build()
    }
}
