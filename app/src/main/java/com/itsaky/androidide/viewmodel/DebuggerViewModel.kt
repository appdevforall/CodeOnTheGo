package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.fragments.debug.EagerStackFrame
import com.itsaky.androidide.fragments.debug.EagerThreadInfo
import com.itsaky.androidide.fragments.debug.EagerVariable
import com.itsaky.androidide.fragments.debug.VariableTreeNodeGenerator
import com.itsaky.androidide.lsp.debug.model.StackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo
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

    val allFrames: StateFlow<List<EagerStackFrame>>
        get() = selectedThread.map { (thread, _) ->
            thread?.getFrames() ?: emptyList()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
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
        viewModelScope.launch(Dispatchers.IO) {
            state.update {
                val threadIndex = if (threads.isNotEmpty()) 0 else -1
                val frameIndex =
                    if (threads.firstOrNull()?.getFrames()?.firstOrNull() != null) 0 else -1
                DebuggerState(
                    threads = threads.map { EagerThreadInfo.create(it) },
                    threadIndex = threadIndex,
                    frameIndex = frameIndex,
                    variablesTree = createVariablesTree(
                        threads.map { EagerThreadInfo.create(it) },
                        threadIndex,
                        frameIndex
                    )
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
        viewModelScope.launch(Dispatchers.IO) {
            state.update { current ->
                check(index in 0..<current.threads.size) {
                    "Invalid thread index: $index"
                }

                val frameIndex = if (current.threads.getOrNull(index)?.getFrames()
                        ?.firstOrNull() != null
                ) 0 else -1
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

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestAllFrames(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: suspend (List<StackFrame>) -> Unit
    ) = viewModelScope.launch(observeOn) {
        allFrames.collectLatest { frames ->
            if (notifyOn != null && notifyOn != coroutineContext[CoroutineDispatcher]) {
                withContext(notifyOn) {
                    consume(frames)
                }
            } else {
                consume(frames)
            }
        }
    }

    fun setSelectedFrameIndex(index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
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