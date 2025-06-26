package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.fragments.debug.ResolvableStackFrame
import com.itsaky.androidide.fragments.debug.ResolvableThreadInfo
import com.itsaky.androidide.fragments.debug.ResolvableVariable
import com.itsaky.androidide.fragments.debug.VariableTreeNodeGenerator
import com.itsaky.androidide.fragments.debug.resolvedOrNull
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.IDEDebugClientImpl
import com.itsaky.androidide.lsp.debug.model.StackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo
import io.github.dingyi222666.view.treeview.Tree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private data class DebuggerState(
    val threads: List<ResolvableThreadInfo>,
    val threadIndex: Int,
    val frameIndex: Int,
    val variablesTree: Tree<ResolvableVariable<*>>
) {
    val selectedThread: ResolvableThreadInfo?
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


enum class DebuggerConnectionState {
    // order of the enum constants matter

    /**
     * Not connected to any remote client.
     */
    DETACHED,

    /**
     * Connected to a remote client, but not yet suspended.
     */
    ATTACHED,

    /**
     * Connected to a remote client and suspended, but not due to a breakpoint hit or step.
     */
    SUSPENDED,

    /**
     * Connected to a remote client and suspended due to a breakpoint hit or step.
     */
    AWAITING_BREAKPOINT,
}

/**
 * @author Akash Yadav
 */
class DebuggerViewModel : ViewModel() {

    private val _connectionState = MutableStateFlow(DebuggerConnectionState.DETACHED)
    private val _debugeePackage = MutableStateFlow("")
    private val state = MutableStateFlow(DebuggerState.DEFAULT)
    internal val debugClient = IDEDebugClientImpl(this)

    init {
        Lookup.getDefault().register(IDEDebugClientImpl::class.java, debugClient)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DebuggerViewModel::class.java)
    }

    val connectionState: StateFlow<DebuggerConnectionState>
        get() = _connectionState.asStateFlow()

    val debugeePackageFlow: StateFlow<String>
        get() = _debugeePackage.asStateFlow()

    var debugeePackage: String
        get() = _debugeePackage.value
        set(value) {
            _debugeePackage.update { value }
        }

    val allThreads: StateFlow<List<ResolvableThreadInfo>>
        get() = state.map { it.threads }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val selectedThread: StateFlow<Pair<ResolvableThreadInfo?, Int>>
        get() = state.map { state ->
            state.selectedThread to state.threadIndex
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null to -1
        )

    val allFrames: StateFlow<List<ResolvableStackFrame>>
        get() = selectedThread.map { (thread, _) ->
            thread?.getFrames() ?: emptyList()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val selectedFrame: StateFlow<Pair<ResolvableStackFrame?, Int>>
        get() = state.map { state ->
            state.selectedFrame() to state.frameIndex
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null to -1
        )

    val selectedFrameVariables: StateFlow<List<ResolvableVariable<*>>>
        get() = selectedFrame.map { (frame, _) ->
            frame?.getVariables() ?: emptyList()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val variablesTree: StateFlow<Tree<ResolvableVariable<*>>>
        get() = state.map { state ->
            state.variablesTree
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DebuggerState.DEFAULT.variablesTree
        )

    override fun onCleared() {
        super.onCleared()
        Lookup.getDefault().unregister(IDEDebugClientImpl::class.java)
    }

    fun setConnectionState(state: DebuggerConnectionState) {
        _connectionState.update { state }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun observeConnectionState(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: suspend (DebuggerConnectionState) -> Unit
    ) = viewModelScope.launch(observeOn) {
        connectionState.collectLatest { state ->
            if (notifyOn != null && notifyOn != coroutineContext[CoroutineDispatcher]) {
                withContext(notifyOn) {
                    consume(state)
                }
            } else {
                consume(state)
            }
        }
    }

    suspend fun setThreads(threads: List<ThreadInfo>) = withContext(Dispatchers.IO) {
        val resolvableThreads = threads.map(ResolvableThreadInfo::create)
            .filter { thread ->
                val descriptor = thread.resolve()
                if (descriptor == null) {
                    logger.warn("Unable to resolve descriptor for thread: $thread")
                    return@filter false
                }

                descriptor.state.isInteractable
            }

        val threadIndex = resolvableThreads.indexOfFirst { it.resolvedOrNull?.state?.isInteractable == true }
        val frameIndex = if (resolvableThreads.getOrNull(threadIndex)?.getFrames()?.firstOrNull() != null) 0 else -1
        val newState = DebuggerState(
            threads = resolvableThreads,
            threadIndex = threadIndex,
            frameIndex = frameIndex,
            variablesTree = createVariablesTree(
                resolvableThreads,
                threadIndex,
                frameIndex
            )
        )

        state.update { newState }
    }

    fun refreshVariables() {
        viewModelScope.launch {
            val currentState = state.value
            val tree = createVariablesTree(currentState.threads, currentState.threadIndex, currentState.frameIndex)
            val newState = currentState.copy(variablesTree = tree)
            state.update { newState }
        }
    }

    private suspend fun createVariablesTree(
        threads: List<ResolvableThreadInfo>,
        threadIndex: Int,
        frameIndex: Int
    ): Tree<ResolvableVariable<*>> = coroutineScope {

        // resolve the data we need to render the UI
        threads.map { thread ->
            async {
                thread.resolve()
            }
        }.awaitAll()

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
        consume: suspend (List<ResolvableThreadInfo>) -> Unit
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

    suspend fun setSelectedThreadIndex(index: Int) = withContext(Dispatchers.IO) {
        state.update { current ->
            check(index in 0..<current.threads.size) {
                "Invalid thread index: $index"
            }

            val thread = current.threads[index]
            if (thread.resolvedOrNull?.state?.isInteractable != true) {
                // thread is non-interactive
                // do not change the thread index
                logger.warn("Attempt to interact with non-interactive thread: $thread")
                return@update current
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

    @OptIn(ExperimentalStdlibApi::class)
    fun observeLatestSelectedThread(
        observeOn: CoroutineDispatcher = Dispatchers.Default,
        notifyOn: CoroutineDispatcher? = null,
        consume: suspend (ResolvableThreadInfo?, Int) -> Unit
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

    suspend fun setSelectedFrameIndex(index: Int) = withContext(Dispatchers.IO) {
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
        consume: suspend (Tree<ResolvableVariable<*>>) -> Unit
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