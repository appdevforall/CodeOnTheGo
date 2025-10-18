package com.itsaky.androidide.agent.repository

import com.google.genai.types.FunctionCall
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.tool.ToolApprovalManager
import com.itsaky.androidide.agent.tool.ToolApprovalResponse
import com.itsaky.androidide.agent.tool.ToolHandler
import com.itsaky.androidide.agent.tool.ToolRouter
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import kotlin.jvm.optionals.getOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutorTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun executeRunsParallelSafeToolsConcurrently() = runTest {
        val startSignals = Channel<String>(capacity = 2)
        val releaseSignals = Channel<Unit>(capacity = 2)
        val readHandler = lambdaHandler("read_file") {
            startSignals.send("read_file")
            releaseSignals.receive()
            ToolResult.success("read_file ok")
        }
        val listHandler = lambdaHandler("list_dir") {
            startSignals.send("list_dir")
            releaseSignals.receive()
            ToolResult.success("list_dir ok")
        }
        val executor = executorWithHandlers(readHandler, listHandler)

        val readCall = functionCall("read_file")
        val listCall = functionCall("list_dir")

        val execution = async { executor.execute(listOf(readCall, listCall)) }

        val started = mutableSetOf<String>()
        withTimeout(5_000) {
            repeat(2) {
                started.add(startSignals.receive())
            }
        }
        assertEquals(setOf("read_file", "list_dir"), started)

        repeat(2) { releaseSignals.send(Unit) }

        val parts = execution.await()
        assertEquals(2, parts.size)
        assertEquals(
            setOf("read_file", "list_dir"),
            parts.mapNotNull { it.functionResponse().getOrNull()?.name()?.getOrNull() }.toSet()
        )
    }

    @Test
    fun executeRunsSequentialToolsSequentially() = runTest {
        val running = AtomicInteger(0)
        val invocationOrder = mutableListOf<String>()
        val createHandler = lambdaHandler("create_file") {
            val previous = running.getAndIncrement()
            if (previous != 0) {
                fail("Sequential tool 'create_file' executed concurrently.")
            }
            invocationOrder.add("start-create_file")
            delay(10)
            invocationOrder.add("end-create_file")
            running.decrementAndGet()
            ToolResult.success("create_file ok")
        }
        val updateHandler = lambdaHandler("update_file") {
            val previous = running.getAndIncrement()
            if (previous != 0) {
                fail("Sequential tool 'update_file' executed concurrently.")
            }
            invocationOrder.add("start-update_file")
            delay(10)
            invocationOrder.add("end-update_file")
            running.decrementAndGet()
            ToolResult.success("update_file ok")
        }
        val executor = executorWithHandlers(createHandler, updateHandler)

        val createCall = functionCall("create_file")
        val updateCall = functionCall("update_file")

        val parts = executor.execute(listOf(createCall, updateCall))

        assertEquals(
            listOf(
                "start-create_file",
                "end-create_file",
                "start-update_file",
                "end-update_file"
            ),
            invocationOrder
        )

        assertEquals(
            listOf("create_file", "update_file"),
            parts.mapNotNull { it.functionResponse().getOrNull()?.name()?.getOrNull() }
        )
    }

    @Test
    fun executeKeepsSequentialResultsBeforeParallelResults() = runTest {
        val executor = executorWithHandlers(
            successHandler("create_file"),
            successHandler("read_file"),
            successHandler("update_file"),
            successHandler("list_dir")
        )

        val calls = listOf(
            functionCall("create_file"),
            functionCall("read_file"),
            functionCall("update_file"),
            functionCall("list_dir")
        )

        val parts = executor.execute(calls)

        assertEquals(
            listOf("create_file", "update_file", "read_file", "list_dir"),
            parts.mapNotNull { it.functionResponse().getOrNull()?.name()?.getOrNull() }
        )
    }

    @Test
    fun executeDeniesToolWhenApprovalRejected() = runTest {
        var invoked = false
        val dangerousHandler = lambdaHandler("update_file", isDangerous = true) {
            invoked = true
            ToolResult.success("update_file ok")
        }
        val denialManager = approvalManager { toolName, _, _ ->
            ToolApprovalResponse(
                approved = false,
                denialMessage = "Denied $toolName"
            )
        }

        val executor = executorWithHandlers(dangerousHandler, approvalManager = denialManager)

        val parts = executor.execute(listOf(functionCall("update_file")))
        assertFalse(invoked)

        @Suppress("UNCHECKED_CAST")
        val response = parts.single().functionResponse().get().response().get() as Map<String, Any?>
        assertEquals(false, response["success"])
        assertEquals("Denied update_file", response["message"])
    }

    private fun functionCall(name: String): FunctionCall {
        val call = mockk<FunctionCall>()
        every { call.name() } returns Optional.of(name)
        every { call.args() } returns Optional.of(emptyMap<String, Any>())
        return call
    }

    private fun executorWithHandlers(
        vararg handlers: ToolHandler,
        approvalManager: ToolApprovalManager = alwaysApproveManager()
    ): Executor {
        val router = ToolRouter(handlers.associateBy { it.name })
        return Executor(router, approvalManager)
    }

    private fun lambdaHandler(
        name: String,
        isDangerous: Boolean = false,
        block: suspend (Map<String, Any?>) -> ToolResult
    ): ToolHandler = object : ToolHandler {
        override val name: String = name
        override val isPotentiallyDangerous: Boolean = isDangerous
        override suspend fun invoke(args: Map<String, Any?>): ToolResult = block(args)
    }

    private fun successHandler(name: String): ToolHandler = lambdaHandler(name) {
        ToolResult.success("$name ok")
    }

    private fun alwaysApproveManager(): ToolApprovalManager = approvalManager { _, _, _ ->
        ToolApprovalResponse(approved = true)
    }

    private fun approvalManager(
        block: suspend (String, ToolHandler, Map<String, Any?>) -> ToolApprovalResponse
    ): ToolApprovalManager = object : ToolApprovalManager {
        override suspend fun ensureApproved(
            toolName: String,
            handler: ToolHandler,
            args: Map<String, Any?>
        ): ToolApprovalResponse = block(toolName, handler, args)
    }
}
