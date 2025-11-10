package com.itsaky.androidide.fragments.debug

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentDebuggerBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.DEBUG_NOT_CONNECTED
import com.itsaky.androidide.idetooltips.TooltipTag.DEBUG_OUTPUT_CALLSTACK
import com.itsaky.androidide.idetooltips.TooltipTag.DEBUG_OUTPUT_VARIABLES
import com.itsaky.androidide.idetooltips.TooltipTag.DEBUG_THREAD_SELECTOR
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadState
import com.itsaky.androidide.utils.isAtLeastR
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.DebuggerConnectionState
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import com.itsaky.androidide.viewmodel.WADBViewModel
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuState
import moe.shizuku.manager.model.ServiceStatus
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku

/**
 * @author Akash Yadav
 */
class DebuggerFragment : EmptyStateFragment<FragmentDebuggerBinding>(FragmentDebuggerBinding::inflate) {
	private var tabs: Array<Pair<String, () -> Fragment>>? = null
	private val viewModel by activityViewModels<DebuggerViewModel>()
	private val wadbViewModel by activityViewModels<WADBViewModel>()
	private var mediator: TabLayoutMediator? = null

	var currentView: Int
		get() = viewModel.currentView
		set(value) {
			if (value == VIEW_WADB_PAIRING && !isAtLeastR()) {
				// WADB pairing is not available on pre-Android11 devices
				throw IllegalStateException("WADB pairing is not supported on this device")
			}

			if (value == VIEW_WADB_PAIRING && Shizuku.pingBinder()) {
				logger.error("Attempt to set current view to pairing mode while Shizuku service is running")
				return
			}

			viewModel.currentView = value
		}

	companion object {
		private val logger = LoggerFactory.getLogger(DebuggerFragment::class.java)

		// values of the following variables are determined by the order in
		// in which they are defined in fragment_debugger.xml
		const val VIEW_DEBUGGER = 0
		const val VIEW_WADB_PAIRING = 1

		const val TABS_COUNT = 2
		const val TAB_INDEX_VARIABLES = 0
		const val TAB_INDEX_CALL_STACK = 1
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		tabs =
			Array(TABS_COUNT) { position ->
				when (position) {
					TAB_INDEX_VARIABLES -> getString(R.string.debugger_variables) to { VariableListFragment() }
					TAB_INDEX_CALL_STACK -> getString(R.string.debugger_call_stack) to { CallStackFragment() }
					else -> throw IllegalStateException("Unknown position: $position")
				}
			}

		binding.debuggerContents.threadLayoutSelector.spinnerLayout.setOnLongPressListener {
			showToolTipDialog(
				DEBUG_THREAD_SELECTOR,
				binding.debuggerContents.threadLayoutSelector.root,
			)
		}

		binding.debuggerContents.debuggerContentContainer.rootView.setOnLongClickListener { view ->
			if (viewModel.connectionState.value == DebuggerConnectionState.DETACHED) {
				showToolTipDialog(DEBUG_NOT_CONNECTED, view)
			}
			true
		}

		viewLifecycleScope.launch(Dispatchers.Main) {
			ShizukuState.reload().await()
		}

		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.CREATED) {
				launch {
					ShizukuState.serviceStatus.collectLatest { currentStatus ->
						withContext(Dispatchers.IO) {
							onShizukuServiceStatusChange(currentStatus)
						}
					}
				}
			}
		}

		viewLifecycleScope.launch {
			viewModel.setThreads(emptyList())

			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				emptyStateViewModel.isEmpty.collectLatest { isEmpty ->
					if (isEmpty) {
						withContext(Dispatchers.Main) {
							binding.debuggerContents.threadLayoutSelector.spinnerText
								.clearListSelection()
						}
					}
				}

				viewModel.observeCurrentView(
					scope = this,
					notifyOn = Dispatchers.Main,
				) { viewIndex ->
					binding.root.displayedChild = viewIndex

					// don't show debugger UI in the following cases
					// 1. current view is not debugger UI
					// 2. current view is debugger UI but not connected to a VM
					// 3. current view is debugger UI but no thread data is available
					val isDebuggerView = currentView == VIEW_DEBUGGER
					val isShizukuConnected = Shizuku.pingBinder()
					val isDebuggerDetached =
						viewModel.connectionState.value < DebuggerConnectionState.ATTACHED
					val hasThreadData = viewModel.allThreads.value.isNotEmpty()
					emptyStateViewModel.setEmpty(isDebuggerView && (!isShizukuConnected || isDebuggerDetached || !hasThreadData))
				}

				viewModel.observeConnectionState(
					scope = this,
					notifyOn = Dispatchers.Main,
				) { state ->
					val showMessage =
						// NOTE: Keep this in sync with getEmptyStateMessage
						when (state) {
							DebuggerConnectionState.DETACHED -> true
							DebuggerConnectionState.ATTACHED -> true
							DebuggerConnectionState.SUSPENDED -> false
							DebuggerConnectionState.AWAITING_BREAKPOINT -> false
						}

					emptyStateViewModel.setEmpty(currentView == VIEW_DEBUGGER && showMessage)
					emptyStateViewModel.setEmptyMessage(getEmptyStateMessage(debuggerConnectionState = state))
				}

				viewModel.observeLatestThreads(
					scope = this,
					notifyOn = Dispatchers.IO,
				) { threads ->
					val descriptors =
						threads
							.map { thread ->
								async { thread.resolve() }
							}.awaitAll()

					withContext(Dispatchers.Main) {
						emptyStateViewModel.setEmpty(currentView == VIEW_DEBUGGER && descriptors.isEmpty())
						binding.debuggerContents.threadLayoutSelector.spinnerText.setAdapter(
							ThreadSelectorListAdapter(
								requireContext(),
								descriptors,
								onItemLongClick = { _, _, _ ->
									showToolTipDialog(
										DEBUG_THREAD_SELECTOR,
										binding.debuggerContents.debuggerContentContainer.rootView,
									)
								},
							),
						)
					}
				}

				viewModel.observeLatestSelectedThread(
					scope = this,
					notifyOn = Dispatchers.IO,
				) { thread, index ->
					if (index < 0) {
						return@observeLatestSelectedThread
					}

					val descriptor = thread!!.resolve()
					withContext(Dispatchers.Main) {
						this@DebuggerFragment.context?.also { context ->
							binding.debuggerContents.threadLayoutSelector.spinnerText.apply {
								listSelection = index
								setText(
									descriptor?.displayText()
										?: context.getString(R.string.debugger_thread_resolution_failure),
									false,
								)
							}
						}
					}
				}
			}
		}

		emptyStateViewModel.setEmptyMessage(getEmptyStateMessage())
		binding.debuggerContents.threadLayoutSelector.spinnerText.setOnItemClickListener { _, _, index, _ ->
			viewLifecycleScope.launch {
				viewModel.setSelectedThreadIndex(index)
			}
		}

		mediator =
			TabLayoutMediator(
				binding.debuggerContents.tabs,
				binding.debuggerContents.pager,
			) { tab, position ->
				tab.text = tabs?.get(position)?.first
				tab.view.setOnLongClickListener { view ->
					when (position) {
						0 -> showToolTipDialog(DEBUG_OUTPUT_VARIABLES, view)
						1 -> showToolTipDialog(DEBUG_OUTPUT_CALLSTACK, view)
					}
					true
				}
			}
		tabs?.let { tab ->
			binding.debuggerContents.pager.adapter =
				DebuggerPagerAdapter(
					childFragmentManager,
					viewLifecycleOwner.lifecycle,
					tab.map { it.second },
				)
		}
		mediator?.attach()
	}

	override fun onDestroyView() {
		mediator?.detach()
		mediator = null
		tabs = null

		with(binding.debuggerContents.threadLayoutSelector.spinnerText) {
			onItemClickListener = null
		}

		binding.debuggerContents.pager.adapter = null

		super.onDestroyView()
	}

	override fun onFragmentLongPressed() {
		showToolTipDialog(DEBUG_NOT_CONNECTED)
	}

	private fun getEmptyStateMessage(
		newMessage: String? = null,
		debuggerConnectionState: DebuggerConnectionState = viewModel.connectionState.value,
		isShizukuServiceRunning: Boolean = Shizuku.pingBinder(),
	): String {
		logger.debug(
			"getEmptyStateMessage called with: newMessage='{}', debuggerConnectionState={}, isShizukuServiceRunning={}",
			newMessage,
			debuggerConnectionState,
			isShizukuServiceRunning,
		)

		if (!isShizukuServiceRunning) {
			logger.debug("Shizuku service is not running. Returning 'not paired' message.")
			return getString(R.string.debugger_state_not_paired)
		}

		val message =
			when (debuggerConnectionState) {
				// not connected to a VM
				DebuggerConnectionState.DETACHED -> {
					logger.debug("Debugger state is DETACHED. Returning 'not connected' message.")
					getString(R.string.debugger_state_not_connected)
				}

				// connected, but not suspended
				DebuggerConnectionState.ATTACHED -> {
					viewModel.debugClient.clientOrNull?.let { client ->
						val connectedMessage =
							getString(
								R.string.debugger_state_connected,
								client.name,
								client.version,
							)
						logger.debug(
							"Debugger state is ATTACHED. Returning message: '{}'",
							connectedMessage,
						)
						connectedMessage
					}
				}

				// ----
				// No need to show any message for below states
				// the debugger UI will show the active threads, variables and call stack when the
				// VM is in one of these states

				// suspended, but not due to a breakpoint hit or step event
				DebuggerConnectionState.SUSPENDED -> {
					logger.debug("Debugger state is SUSPENDED. No message to show.")
					null
				}
				// suspended due to a breakpoint hit or step event
				DebuggerConnectionState.AWAITING_BREAKPOINT -> {
					logger.debug("Debugger state is AWAITING_BREAKPOINT. No message to show.")
					null
				}
			}

		if (message != null) {
			logger.debug("Returning message from when-statement: '{}'", message)
			return message
		}

		if (newMessage != null) {
			logger.debug("Returning provided newMessage: '{}'", newMessage)
			return newMessage
		}

		logger.debug("No specific message found. Returning default 'not connected' message.")
		return getString(R.string.debugger_state_not_connected)
	}

	fun showToolTipDialog(
		tag: String,
		anchorView: View? = null,
	) {
		anchorView?.let {
			TooltipManager.showIdeCategoryTooltip(requireContext(), it, tag)
		}
	}

	private fun onShizukuServiceStatusChange(status: ServiceStatus?) {
		logger.debug("Shizuku service status changed: {}", status)
		var newView = VIEW_DEBUGGER

		val isAtLeastR = isAtLeastR()
		val isShizukuRunning = status?.isRunning == true
		val wadbPairingState = wadbViewModel.pairingState.value

		logger.debug(
			"Evaluating conditions to switch view: isAtLeastR={}, isShizukuRunning={}, wadbPairingState={}",
			isAtLeastR,
			isShizukuRunning,
			wadbPairingState,
		)

		if (isAtLeastR() &&
			status?.isRunning != true &&
			wadbViewModel.pairingState.value == WADBViewModel.PairingState.Connecting
		) {
			logger.debug("Conditions met: Showing WADB pairing view.")
			// show the pairing screen only when Shizuku is in the connecting state
			// and not already connected
			newView = VIEW_WADB_PAIRING
		}

		logger.debug(
			"Setting current view to: {}",
			if (newView == VIEW_DEBUGGER) "VIEW_DEBUGGER" else "VIEW_WADB_PAIRING",
		)
		viewModel.currentView = newView
		emptyStateViewModel.setEmptyMessage(getEmptyStateMessage())
	}
}

class DebuggerPagerAdapter(
	fragmentManager: FragmentManager,
	lifecycle: Lifecycle,
	private val factories: List<() -> Fragment>,
) : FragmentStateAdapter(fragmentManager, lifecycle) {
	override fun getItemCount(): Int = factories.size

	override fun createFragment(position: Int): Fragment = factories[position].invoke()
}

class ThreadSelectorListAdapter(
	context: Context,
	items: List<ThreadDescriptor?>,
	private val onItemLongClick: ((ThreadDescriptor, Int, View) -> Unit),
) : ArrayAdapter<ThreadDescriptor?>(context, android.R.layout.simple_dropdown_item_1line, items) {
	@SuppressLint("ClickableViewAccessibility")
	override fun getView(
		position: Int,
		convertView: View?,
		parent: ViewGroup,
	): View {
		val inflater = LayoutInflater.from(this.context)
		val view =
			(
				convertView ?: inflater.inflate(
					android.R.layout.simple_dropdown_item_1line,
					parent,
					false,
				)
			) as TextView

		val item = getItem(position)
		if (item == null) {
			view.text = context.getString(R.string.debugger_thread_resolution_failure)
			return view
		}

		val isEnabled = item.state != ThreadState.UNKNOWN && item.state != ThreadState.ZOMBIE

		if (isEnabled) {
			var longPressDetected = false

			val gestureDetector =
				GestureDetector(
					context,
					object : GestureDetector.SimpleOnGestureListener() {
						override fun onLongPress(e: MotionEvent) {
							longPressDetected = true
							try {
								view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
								if (view.isAttachedToWindow) {
									onItemLongClick.invoke(item, position, view)
								}
							} catch (e: Exception) {
								Sentry.captureException(e)
							}
						}

						override fun onDown(e: MotionEvent): Boolean {
							longPressDetected = false
							return true
						}
					},
				)

			view.setOnTouchListener { v, event ->
				gestureDetector.onTouchEvent(event)
				longPressDetected
			}
		} else {
			view.setOnTouchListener(null)
		}

		view.isEnabled = isEnabled
		view.text = item.displayText()
		view.alpha = if (isEnabled) 1f else 0.5f
		return view
	}
}
