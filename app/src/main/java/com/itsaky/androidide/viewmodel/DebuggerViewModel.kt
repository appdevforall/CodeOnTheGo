package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue
import com.itsaky.androidide.lsp.debug.model.PrimitiveVariable
import com.itsaky.androidide.lsp.debug.model.StackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo
import com.itsaky.androidide.lsp.debug.model.Value
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class SimpleThreadInfo(
    private val name: String, private val frames: List<StackFrame>
) : ThreadInfo {
    override fun getName(): String = name
    override fun getFrames(): List<StackFrame> = frames

    override fun toString(): String = name
}

private class SimpleStackFrame(
    private val variables: List<Variable<*>>
) : StackFrame {
    override fun getVariables(): List<Variable<*>> = variables

    override fun <Val : Value> setValue(variable: Variable<Val>, value: Val) {
        TODO("Not yet implemented")
    }

    override fun toString(): String = "at com.some.Class.someMethod(Class:123)"
}

private class SimplePrimitiveValue(
    override val value: String,
    override val kind: PrimitiveKind,
) : PrimitiveValue {
    override fun asByte(): Byte = value.toByte()
    override fun asShort(): Short = value.toShort()
    override fun asInt(): Int = value.toInt()
    override fun asLong(): Long = value.toLong()
    override fun asFloat(): Float = value.toFloat()
    override fun asDouble(): Double = value.toDouble()
    override fun asBoolean(): Boolean = value.toBoolean()
    override fun asChar(): Char = value.toCharArray()[0]

    override fun asString(): String = value
    override fun toString(): String = asString()
}

private class SimpleVariable(
    override val name: String,
    override val typeName: String,
    override val primitiveKind: PrimitiveKind,
    val value: String,
) : PrimitiveVariable {
    override val kind = VariableKind.PRIMITIVE
    override fun value(): PrimitiveValue = SimplePrimitiveValue(value, primitiveKind)
    override fun toString(): String = "$name: $typeName"
}

private data class DebuggerState(
    val threads: List<ThreadInfo>,
    val threadIndex: Int,
    val frameIndex: Int,
) {
    val selectedThread: ThreadInfo?
        get() = threads.getOrNull(threadIndex)

    val selectedFrame: StackFrame?
        get() = selectedThread?.getFrames()?.getOrNull(frameIndex)

    companion object {
        val DEFAULT = DebuggerState(
            threads = emptyList(),
            threadIndex = -1,
            frameIndex = -1
        )
    }
}

/**
 * @author Akash Yadav
 */
class DebuggerViewModel : ViewModel() {

    companion object {
        fun demoThreads(): List<ThreadInfo> {
            val threads = mutableListOf<ThreadInfo>()
            for (i in 1..10) {
                val frames = mutableListOf<StackFrame>()
                for (j in 1..10) {
                    val variables = mutableListOf<Variable<*>>()
                    for (k in 1..10) {
                        variables.add(
                            SimpleVariable(
                                "thread${i}Frame${j}Var${k}", "int", PrimitiveKind.INT, "$k"
                            )
                        )
                    }
                    frames.add(SimpleStackFrame(variables))
                }
                threads.add(SimpleThreadInfo("Thread $i", frames))
            }
            return threads
        }
    }

    private val state = MutableStateFlow(DebuggerState.DEFAULT)

    val allThreads: StateFlow<List<ThreadInfo>>
        get() = state.map { it.threads }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    val selectedThread: StateFlow<Pair<ThreadInfo?, Int>>
        get() = state.map { state ->
            state.selectedThread to state.threadIndex
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null to -1
        )

    val selectedFrame: StateFlow<Pair<StackFrame?, Int>>
        get() = state.map { state ->
            state.selectedFrame to state.frameIndex
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null to -1
        )

    val selectedFrameVariables: StateFlow<List<Variable<*>>>
        get() = selectedFrame.map { (frame, _) ->
            frame?.getVariables() ?: emptyList()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    fun setThreads(threads: List<ThreadInfo>) {
        state.update {
            val threadIndex = if (threads.isNotEmpty()) 0 else -1
            val frameIndex = if (threadIndex >= 0) 0 else -1
            DebuggerState(
                threads = threads,
                threadIndex = threadIndex,
                frameIndex = frameIndex
            )
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestThreads(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: (List<ThreadInfo>) -> Unit
    ) = viewModelScope.launch(observeOn) {
        allThreads.collectLatest { threads ->
            if (notifyOn != null && notifyOn != coroutineContext[CoroutineDispatcher]) {
                withContext(notifyOn) {
                    consume(threads)
                }
            } else {
                consume(threads)
            }
        }
    }

    fun setSelectedThreadIndex(index: Int) {
        state.update { current ->
            check(index in 0..<current.threads.size) {
                "Invalid thread index: $index"
            }

            current.copy(threadIndex = index)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestSelectedThread(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: (ThreadInfo?, Int) -> Unit
    ) = viewModelScope.launch(observeOn) {
        selectedThread.collectLatest { (thread, index) ->
            if (notifyOn != null && notifyOn != coroutineContext[CoroutineDispatcher]) {
                withContext(notifyOn) {
                    consume(thread, index)
                }
            } else {
                consume(thread, index)
            }
        }
    }

    fun setSelectedFrameIndex(index: Int) {
        state.update { current ->
            check(index in 0..<(current.selectedThread?.getFrames()?.size ?: 0)) {
                "Invalid frame index: $index"
            }

            current.copy(frameIndex = index)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestSelectedFrame(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: (StackFrame?, Int) -> Unit,
    ) = viewModelScope.launch(observeOn) {
        selectedFrame.collectLatest { (frame, index) ->
            if (notifyOn != null && notifyOn != coroutineContext[CoroutineDispatcher]) {
                withContext(notifyOn) {
                    consume(frame, index)
                }
            } else {
                consume(frame, index)
            }
        }
    }
}