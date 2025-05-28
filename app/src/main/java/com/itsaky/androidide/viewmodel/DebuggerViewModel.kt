package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.fragments.debug.EagerStackFrame
import com.itsaky.androidide.fragments.debug.EagerThreadInfo
import com.itsaky.androidide.fragments.debug.EagerVariable
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
    override suspend fun getFrames(): List<StackFrame> = frames

    override fun toString(): String = name
}

private class SimpleStackFrame(
    private val variables: List<EagerVariable<*>>
) : StackFrame {
    override suspend fun getVariables(): List<EagerVariable<*>> = variables

    override suspend fun <Val : Value> setValue(variable: Variable<Val>, value: Val) {
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
    override val kind: VariableKind = VariableKind.PRIMITIVE,
    val members: Set<EagerVariable<*>> = emptySet(),
) : PrimitiveVariable {
    override suspend fun value(): PrimitiveValue = SimplePrimitiveValue(value, primitiveKind)
    override suspend fun isMutable(): Boolean = false
    override suspend fun objectMembers(): Set<EagerVariable<*>> = members
    override fun toString(): String = name
}

private data class DebuggerState(
    val threads: List<EagerThreadInfo>,
    val threadIndex: Int,
    val frameIndex: Int,
    val variablesTree: Tree<EagerVariable<*>>
) {
    val selectedThread: EagerThreadInfo?
        get() = threads.getOrNull(threadIndex)

    suspend fun selectedFrame() = selectedThread?.getFrames()?.getOrNull(frameIndex)

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

        suspend fun demoThreads(): List<EagerThreadInfo> {
            val threads = mutableListOf<EagerThreadInfo>()
            for (i in 1..10) {
                val frames = mutableListOf<StackFrame>()
                for (j in 1..10) {
                    val variables = mutableListOf<EagerVariable<*>>()
                    for (k in 1..10) {
                        variables.add(
                            EagerVariable.create(
                                SimpleVariable( 
                                    name = "thread${i}Frame${j}Var${k}",
                                    typeName = "int",
                                    kind = VariableKind.entries.filter { it != VariableKind.UNKNOWN }.random(),
                                    primitiveKind = PrimitiveKind.INT,
                                    value = "$k",
                                    members = (1..2).map { mem ->
                                        EagerVariable.create(
                                            SimpleVariable(
                                                name = "thread${i}Frame${j}Var${k}Member${mem}",
                                                typeName = "int",
                                                kind = VariableKind.entries.filter { it != VariableKind.UNKNOWN }.random(),
                                                primitiveKind = PrimitiveKind.INT,
                                                value = "$k",
                                            )
                                        )
                                    }.toSet()
                                )
                            )
                        )
                    }
                    frames.add(SimpleStackFrame(variables))
                }

                threads.add(EagerThreadInfo.create(SimpleThreadInfo("Thread $i", frames)))
            }
            return threads
        }
    }

    private val state = MutableStateFlow(DebuggerState.DEFAULT)

    val allThreads: StateFlow<List<EagerThreadInfo>>
        get() = state.map { it.threads }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val selectedThread: StateFlow<Pair<EagerThreadInfo?, Int>>
        get() = state.map { state ->
            state.selectedThread to state.threadIndex
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null to -1
        )

    val selectedFrame: StateFlow<Pair<EagerStackFrame?, Int>>
        get() = state.map { state ->
            state.selectedFrame() to state.frameIndex
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null to -1
        )

    val selectedFrameVariables: StateFlow<List<EagerVariable<*>>>
        get() = selectedFrame.map { (frame, _) ->
            frame?.getVariables() ?: emptyList()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val variablesTree: StateFlow<Tree<EagerVariable<*>>>
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
                    threads = threads.map { EagerThreadInfo.create(it) },
                    threadIndex = threadIndex,
                    frameIndex = frameIndex,
                    variablesTree = createVariablesTree(threads.map { EagerThreadInfo.create(it) }, threadIndex, frameIndex)
                )
            }
        }
    }

    private suspend fun createVariablesTree(
        threads: List<EagerThreadInfo>,
        threadIndex: Int,
        frameIndex: Int
    ): Tree<EagerVariable<*>> = coroutineScope {
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
        consume: suspend (List<EagerThreadInfo>) -> Unit
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
        consume: suspend (EagerThreadInfo?, Int) -> Unit
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
        consume: suspend (Tree<EagerVariable<*>>) -> Unit
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