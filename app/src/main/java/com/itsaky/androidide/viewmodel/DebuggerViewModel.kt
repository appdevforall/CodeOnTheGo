package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.fragments.debug.VariableTreeNodeGenerator
import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue
import com.itsaky.androidide.lsp.debug.model.PrimitiveVariable
import com.itsaky.androidide.lsp.debug.model.StackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo
import com.itsaky.androidide.lsp.debug.model.Value
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableKind
import io.github.dingyi222666.view.treeview.Tree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

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
    val members: Set<Variable<*>> = emptySet(),
) : PrimitiveVariable {
    override val kind = VariableKind.PRIMITIVE
    override suspend fun value(): PrimitiveValue = SimplePrimitiveValue(value, primitiveKind)
    override suspend fun isMutable(): Boolean = false
    override suspend fun objectMembers(): Set<Variable<*>> = members
    override fun toString(): String = name
}

private data class DebuggerState(
    val threads: List<ThreadInfo>,
    val threadIndex: Int,
    val frameIndex: Int,
    val variablesTree: Tree<Variable<*>>
) {
    val selectedThread: ThreadInfo?
        get() = threads.getOrNull(threadIndex)

    val selectedFrame: StackFrame?
        get() = selectedThread?.getFrames()?.getOrNull(frameIndex)

    companion object {
        val DEFAULT = DebuggerState(
            threads = emptyList(),
            threadIndex = -1,
            frameIndex = -1,
            variablesTree = Tree.createTree(
                VariableTreeNodeGenerator.newInstance(emptySet())
            ),
        )
    }
}

/**
 * @author Akash Yadav
 */
class DebuggerViewModel : ViewModel() {

    companion object {

        private val logger = LoggerFactory.getLogger(DebuggerViewModel::class.java)

        fun demoThreads(): List<ThreadInfo> {
            val threads = mutableListOf<ThreadInfo>()
            for (i in 1..10) {
                val frames = mutableListOf<StackFrame>()
                for (j in 1..10) {
                    val variables = mutableListOf<Variable<*>>()
                    for (k in 1..10) {
                        variables.add(
                            SimpleVariable(
                                name = "thread${i}Frame${j}Var${k}",
                                typeName = "int",
                                primitiveKind = PrimitiveKind.INT,
                                value = "$k",
                                members = (1..2).map { mem ->
                                    SimpleVariable(
                                        name = "thread${i}Frame${j}Var${k}Member${mem}",
                                        typeName = "int",
                                        primitiveKind = PrimitiveKind.INT,
                                        value = "$k",
                                    )
                                }.toSet()
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
        get() = state.map { it.threads }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val selectedThread: StateFlow<Pair<ThreadInfo?, Int>>
        get() = state.map { state ->
            state.selectedThread to state.threadIndex
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null to -1
        )

    val selectedFrame: StateFlow<Pair<StackFrame?, Int>>
        get() = state.map { state ->
            state.selectedFrame to state.frameIndex
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null to -1
        )

    val selectedFrameVariables: StateFlow<List<Variable<*>>>
        get() = selectedFrame.map { (frame, _) ->
            frame?.getVariables() ?: emptyList()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val variablesTree: StateFlow<Tree<Variable<*>>>
        get() = state.map { state ->
            state.variablesTree
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DebuggerState.DEFAULT.variablesTree
        )

    fun setThreads(threads: List<ThreadInfo>) {
        viewModelScope.launch {
            state.update {
                val threadIndex = if (threads.isNotEmpty()) 0 else -1
                val frameIndex = if (threads.firstOrNull()?.getFrames()?.firstOrNull() != null) 0 else -1
                DebuggerState(
                    threads = threads,
                    threadIndex = threadIndex,
                    frameIndex = frameIndex,
                    variablesTree = createVariablesTree(threads, threadIndex, frameIndex)
                )
            }
        }
    }

    private suspend fun createVariablesTree(
        threads: List<ThreadInfo>, threadIndex: Int, frameIndex: Int
    ): Tree<Variable<*>> = coroutineScope {
        val roots =
            threads.getOrNull(threadIndex)
                ?.getFrames()
                ?.getOrNull(frameIndex)
                ?.getVariables()

        Tree.createTree(VariableTreeNodeGenerator.newInstance(roots?.toSet() ?: emptySet()))
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestThreads(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: suspend (List<ThreadInfo>) -> Unit
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
        viewModelScope.launch {
            state.update { current ->
                check(index in 0..<current.threads.size) {
                    "Invalid thread index: $index"
                }

                val frameIndex = if (current.threads.getOrNull(index)?.getFrames()?.firstOrNull() != null) 0 else -1
                current.copy(
                    threadIndex = index,
                    frameIndex = frameIndex,
                    variablesTree = createVariablesTree(current.threads, index, frameIndex)
                )
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestSelectedThread(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: suspend (ThreadInfo?, Int) -> Unit
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
        viewModelScope.launch {
            state.update { current ->
                check(index in 0..<(current.selectedThread?.getFrames()?.size ?: 0)) {
                    "Invalid frame index: $index"
                }

                current.copy(
                    frameIndex = index,
                    variablesTree = createVariablesTree(current.threads, current.threadIndex, index)
                )
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestSelectedFrame(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: suspend (StackFrame?, Int) -> Unit,
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

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestVariablesTree(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: suspend (Tree<Variable<*>>) -> Unit
    ) = viewModelScope.launch(observeOn) {
        variablesTree.collectLatest { tree ->
            if (notifyOn != null && notifyOn != coroutineContext[CoroutineDispatcher]) {
                withContext(notifyOn) {
                    consume(tree)
                }
            } else {
                consume(tree)
            }
        }
    }
}