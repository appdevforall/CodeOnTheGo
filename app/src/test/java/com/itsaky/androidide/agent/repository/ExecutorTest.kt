package com.itsaky.androidide.agent.repository

import com.google.genai.types.FunctionCall
import com.itsaky.androidide.agent.model.ToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Suppress("UNCHECKED_CAST")
    private fun spiedExecutorWithHandler(handler: suspend (FunctionCall) -> ToolResult): Executor {
        val executor = spyk(Executor(), recordPrivateCalls = true)
        coEvery { executor["dispatchToolCall"](any<FunctionCall>()) } coAnswers {
            handler(this.invocation.args[0] as FunctionCall)
        }
        return executor
    }

    @Test
    fun executeRunsParallelSafeToolsConcurrently() = runTest {
        val startSignals = Channel<String>(capacity = 2)
        val releaseSignals = Channel<Unit>(capacity = 2)
        val executor = spiedExecutorWithHandler { call ->
            val name = call.name().getOrNull().orEmpty()
            if (name == "read_file" || name == "list_files") {
                startSignals.send(name)
                releaseSignals.receive()
            }
            ToolResult.success("$name ok")
        }

        val readCall = functionCall("read_file")
        val listCall = functionCall("list_files")

        val execution = async { executor.execute(listOf(readCall, listCall)) }

        val started = mutableSetOf<String>()
        withTimeout(5_000) {
            repeat(2) {
                started.add(startSignals.receive())
            }
        }
        assertEquals(setOf("read_file", "list_files"), started)

        repeat(2) { releaseSignals.send(Unit) }

        val parts = execution.await()
        assertEquals(2, parts.size)
        assertEquals(
            setOf("read_file", "list_files"),
            parts.mapNotNull { it.functionResponse().getOrNull()?.name()?.getOrNull() }.toSet()
        )
    }

    @Test
    fun executeRunsSequentialToolsSequentially() = runTest {
        val running = AtomicInteger(0)
        val invocationOrder = mutableListOf<String>()
        val executor = spiedExecutorWithHandler { call ->
            val name = call.name().getOrNull().orEmpty()
            when (name) {
                "create_file", "update_file" -> {
                    val previous = running.getAndIncrement()
                    if (previous != 0) {
                        fail("Sequential tool '$name' executed concurrently.")
                    }
                    invocationOrder.add("start-$name")
                    delay(10)
                    invocationOrder.add("end-$name")
                    running.decrementAndGet()
                    ToolResult.success("$name ok")
                }

                else -> ToolResult.success("$name ok")
            }
        }

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
        val executor = spiedExecutorWithHandler { call ->
            val name = call.name().getOrNull().orEmpty()
            ToolResult.success("$name ok")
        }

        val calls = listOf(
            functionCall("create_file"),
            functionCall("read_file"),
            functionCall("update_file"),
            functionCall("list_files")
        )

        val parts = executor.execute(calls)

        assertEquals(
            listOf("create_file", "update_file", "read_file", "list_files"),
            parts.mapNotNull { it.functionResponse().getOrNull()?.name()?.getOrNull() }
        )
    }

    private fun functionCall(name: String): FunctionCall {
        val call = mockk<FunctionCall>()
        every { call.name() } returns Optional.of(name)
        every { call.args() } returns Optional.of(emptyMap<String, Any>())
        return call
    }
}
