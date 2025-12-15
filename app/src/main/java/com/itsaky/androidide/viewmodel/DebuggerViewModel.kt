package com.itsaky.androidide.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.fragments.debug.DebuggerFragment
import com.itsaky.androidide.fragments.debug.ResolvableThreadInfo
import com.itsaky.androidide.fragments.debug.ResolvableVariable
import com.itsaky.androidide.fragments.debug.VariableTreeNodeGenerator
import com.itsaky.androidide.fragments.debug.resolvedOrNull
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.IDEDebugClientImpl
import com.itsaky.androidide.lsp.debug.model.StackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo
import com.itsaky.androidide.utils.PrivilegedActions
import io.github.dingyi222666.view.treeview.Tree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku

private data class DebuggerState(
	val threads: List<ResolvableThreadInfo>,
	val threadIndex: Int,
	val frameIndex: Int,
	val variablesTree: Tree<ResolvableVariable<*>>,
) {
	val selectedThread: ResolvableThreadInfo?
		get() = threads.getOrNull(threadIndex)

	suspend fun selectedFrame() = selectedThread?.getFrames()?.getOrNull(frameIndex)

	companion object {
		val DEFAULT =
			DebuggerState(
				threads = emptyList(),
				threadIndex = -1,
				frameIndex = -1,
				variablesTree =
					Tree.createTree(
						VariableTreeNodeGenerator.newInstance(emptySet()),
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
	private val _currentView = MutableStateFlow(DebuggerFragment.VIEW_DEBUGGER)
	private val state = MutableStateFlow(DebuggerState.DEFAULT)
	internal val debugClient = IDEDebugClientImpl(this)

	init {
		Lookup.getDefault().update(IDEDebugClientImpl::class.java, debugClient)
	}

	companion object {
		private val logger = LoggerFactory.getLogger(DebuggerViewModel::class.java)
	}

	var currentView: Int
		get() = _currentView.value
		set(value) = _currentView.update { value }

	val connectionState = _connectionState.asStateFlow()

	val debugeePackageFlow = _debugeePackage.asStateFlow()

	var debugeePackage: String
		get() = _debugeePackage.value
		set(value) {
			_debugeePackage.update { value }
		}

	val allThreads = state.map {
		logger.debug("Updating all threads")
		it.threads
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.Eagerly,
		initialValue = emptyList(),
	)

	val selectedThread = state
		.map { state ->
			state.selectedThread to state.threadIndex
		}.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Eagerly,
			initialValue = null to -1,
		)

	val allFrames = selectedThread
		.map { (thread, _) ->
			thread?.getFrames() ?: emptyList()
		}.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Eagerly,
			initialValue = emptyList(),
		)

	val selectedFrame = state
		.map { state ->
			state.selectedFrame() to state.frameIndex
		}.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Eagerly,
			initialValue = null to -1,
		)

	val selectedFrameVariables = selectedFrame
		.map { (frame, _) ->
			frame?.getVariables() ?: emptyList()
		}.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Eagerly,
			initialValue = emptyList(),
		)

	val variablesTree = state
		.map { state ->
			state.variablesTree
		}.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Eagerly,
			initialValue = DebuggerState.DEFAULT.variablesTree,
		)

	override fun onCleared() {
		super.onCleared()
		Lookup.getDefault().unregister(IDEDebugClientImpl::class.java)
	}

	fun setConnectionState(state: DebuggerConnectionState) {
		_connectionState.update { state }
	}

	private fun setDebuggerState(newState: DebuggerState, because: String) {
		logger.debug("Updating debugger state because {}: {}", because, newState)
		state.update { newState }
	}

	private suspend inline fun setDebuggerState(
		because: String,
		crossinline newState: suspend (DebuggerState) -> DebuggerState
	) {
		val currentState = state.value
		val newState = newState(currentState)
		setDebuggerState(newState, because)
	}

	@OptIn(ExperimentalStdlibApi::class)
	fun observeCurrentView(
		scope: CoroutineScope = viewModelScope,
		observeOn: CoroutineDispatcher = Dispatchers.Default,
		notifyOn: CoroutineDispatcher? = null,
		consume: suspend (Int) -> Unit,
	) = scope.launch(observeOn) {
		_currentView.collectLatest { viewIndex ->
			if (notifyOn != null && notifyOn != coroutineContext[CoroutineDispatcher]) {
				withContext(notifyOn) {
					consume(viewIndex)
				}
			} else {
				consume(viewIndex)
			}
		}
	}

	@OptIn(ExperimentalStdlibApi::class)
	fun observeConnectionState(
		scope: CoroutineScope = viewModelScope,
		observeOn: CoroutineDispatcher = Dispatchers.Default,
		notifyOn: CoroutineDispatcher? = null,
		consume: suspend (DebuggerConnectionState) -> Unit,
	) = scope.launch(observeOn) {
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

	suspend fun setThreads(
		threads: List<ThreadInfo>,
		selectedThreadIndex: Int = -1,
		selectedFrameIndex: Int = -1
	) {
		logger.debug(
			"setThreads(selectedThreadIndex={}, selectedFrameIndex={}, threads={})",
			selectedThreadIndex,
			selectedFrameIndex,
			threads
		)

		withContext(Dispatchers.IO) {
			setDebuggerState(because = "new thread data available") {
				val resolvableThreads =
					threads
						.map(ResolvableThreadInfo::create)
						.filter { thread ->
							val descriptor = thread.resolve()
							if (descriptor == null) {
								logger.warn("Unable to resolve descriptor for thread: $thread")
								return@filter false
							}

							descriptor.state.isInteractable
						}

				var threadIndex =
					if (selectedThreadIndex >= 0 && selectedThreadIndex < threads.size) {
						// Find the matching thread in the filtered list by ID
						val targetThreadId =
							threads.getOrNull(selectedThreadIndex)?.descriptor()?.id
						resolvableThreads.indexOfFirst { it.resolvedOrNull?.id == targetThreadId }
					} else {
						-1
					}

				if (threadIndex < 0) {
					threadIndex =
						resolvableThreads.indexOfFirst { it.resolvedOrNull?.state?.isInteractable == true }
				}

				var frameIndex = selectedFrameIndex
				if (frameIndex < 0) {
					frameIndex = if (resolvableThreads
							.getOrNull(threadIndex)
							?.getFrames()
							?.firstOrNull() != null
					) 0 else -1
				}

				DebuggerState(
					threads = resolvableThreads,
					threadIndex = threadIndex,
					frameIndex = frameIndex,
					variablesTree =
						createVariablesTree(
							threads = resolvableThreads,
							threadIndex = threadIndex,
							frameIndex = frameIndex,
							resolve = false,
						),
				)
			}
		}
	}

	fun refreshState() {
		viewModelScope.launch {
			debugClient.updateThreadInfo(
				debugClient.requireClient,
			)
		}
	}

	private suspend fun createVariablesTree(
		threads: List<ResolvableThreadInfo>,
		threadIndex: Int,
		frameIndex: Int,
		resolve: Boolean = true,
	): Tree<ResolvableVariable<*>> =
		coroutineScope {
			if (resolve) {
				// resolve the data we need to render the UI
				threads.forEach { thread ->
					thread.resolve()
				}
			}

			val roots =
				threads
					.getOrNull(threadIndex)
					?.getFrames()
					?.getOrNull(frameIndex)
					?.getVariables()

			Tree.createTree(VariableTreeNodeGenerator.newInstance(roots?.toSet() ?: emptySet()))
		}

	@OptIn(ExperimentalStdlibApi::class)
	fun observeLatestThreads(
		scope: CoroutineScope = viewModelScope,
		observeOn: CoroutineDispatcher = Dispatchers.Default,
		notifyOn: CoroutineDispatcher? = null,
		consume: suspend (List<ResolvableThreadInfo>) -> Unit,
	) = scope.launch(observeOn) {
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
		setDebuggerState(because = "selected thread index changed") { current ->
			check(index in 0..<current.threads.size) {
				"Invalid thread index: $index"
			}

			val thread = current.threads[index]
			if (thread.resolvedOrNull?.state?.isInteractable != true) {
				// thread is non-interactive
				// do not change the thread index
				logger.warn("Attempt to interact with non-interactive thread: $thread")
				return@setDebuggerState current
			}

			val frameIndex =
				if (current.threads
						.getOrNull(index)
						?.getFrames()
						?.firstOrNull() != null
				) {
					0
				} else {
					-1
				}

			current.copy(
				threadIndex = index,
				frameIndex = frameIndex,
				variablesTree = createVariablesTree(current.threads, index, frameIndex),
			)
		}
	}

	@OptIn(ExperimentalStdlibApi::class)
	fun observeLatestSelectedThread(
		scope: CoroutineScope = viewModelScope,
		observeOn: CoroutineDispatcher = Dispatchers.Default,
		notifyOn: CoroutineDispatcher? = null,
		consume: suspend (ResolvableThreadInfo?, Int) -> Unit,
	) = scope.launch(observeOn) {
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
		scope: CoroutineScope = viewModelScope,
		observeOn: CoroutineDispatcher = Dispatchers.Default,
		notifyOn: CoroutineDispatcher? = null,
		consume: suspend (List<StackFrame>) -> Unit,
	) = scope.launch(observeOn) {
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
		setDebuggerState(because = "selected frame index changed") { current ->
			check(index in 0..<(current.selectedThread?.getFrames()?.size ?: 0)) {
				"Invalid frame index: $index"
			}

			current.copy(
				frameIndex = index,
				variablesTree =
					createVariablesTree(
						current.threads,
						current.threadIndex,
						index,
					),
			)
		}
	}

	@OptIn(ExperimentalStdlibApi::class)
	fun observeLatestSelectedFrame(
		scope: CoroutineScope = viewModelScope,
		observeOn: CoroutineDispatcher = Dispatchers.Default,
		notifyOn: CoroutineDispatcher? = null,
		consume: suspend (StackFrame?, Int) -> Unit,
	) = scope.launch(observeOn) {
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
		scope: CoroutineScope = viewModelScope,
		observeOn: CoroutineDispatcher = Dispatchers.Default,
		notifyOn: CoroutineDispatcher? = null,
		consume: suspend (Tree<ResolvableVariable<*>>) -> Unit,
	) = scope.launch(observeOn) {
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

	fun switchToIde(context: Context) {
		val packageManager = context.packageManager
		val launchIntent = packageManager.getLaunchIntentForPackage(BuildInfo.PACKAGE_NAME)
		val component = launchIntent?.component

		val launchWithIntent: () -> Unit = {
			runCatching {
				// fall back to startActivity
				context.startActivity(launchIntent)
			}.onFailure { err ->
				logger.error("Failed to switch to IDE using intent: {}", launchIntent, err)
			}
		}

		if (launchIntent != null && component != null) {
			if (!Shizuku.pingBinder()) {
				logger.warn("Shizuku is not running, falling back to startActivity.")
				launchWithIntent()
				return
			}

			viewModelScope.launch {
				val launchSuccessful =
					PrivilegedActions.launchApp(
						component = component,
						action = launchIntent.action ?: Intent.ACTION_MAIN,
						categories = launchIntent.categories ?: setOf(Intent.CATEGORY_LAUNCHER),
						forceStop = false,
						debugMode = false,
					)

				if (!launchSuccessful) {
					logger.warn("Failed to launch IDE using privileged APIs. Falling back to startActivity.")
					withContext(Dispatchers.Main.immediate) {
						launchWithIntent()
					}
				}
			}
		} else {
			logger.error(
				"Unable to switch to IDE. Cannot find launch intent for package {}. Found intent: {}",
				BuildInfo.PACKAGE_NAME,
				launchIntent,
			)
		}
	}
}
