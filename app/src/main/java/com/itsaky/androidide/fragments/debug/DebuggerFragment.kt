package com.itsaky.androidide.fragments.debug

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentDebuggerBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadState
import com.itsaky.androidide.utils.isAtLeastR
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.DebuggerConnectionState
import com.itsaky.androidide.viewmodel.DebuggerViewModel
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
	private lateinit var tabs: Array<Pair<String, () -> Fragment>>
	private val viewModel by activityViewModels<DebuggerViewModel>()

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

		emptyStateViewModel.isEmpty.observe(viewLifecycleOwner) { isEmpty ->
			if (isEmpty) {
				binding.debuggerContents.threadLayoutSelector.spinnerText
					.clearListSelection()
			}
		}

		viewLifecycleScope.launch(Dispatchers.Main) {
			ShizukuState.reload().await()
		}

		viewLifecycleScope.launch {
			viewModel.setThreads(emptyList())

			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.observeCurrentView(
					scope = this,
					notifyOn = Dispatchers.Main,
				) { viewIndex ->
					binding.root.displayedChild = viewIndex

					// don't show debugger UI in the following cases
					// 1. current view is not debugger UI
					// 2. current view is debugger UI but not connected to a VM
					// 3. current view is debugger UI but no thread data is available
					emptyStateViewModel.isEmpty.value =
						currentView == VIEW_DEBUGGER &&
						(
							viewModel.connectionState.value < DebuggerConnectionState.ATTACHED ||
								viewModel.allThreads.value.isEmpty()
						)
				}

				viewModel.observeConnectionState(
					scope = this,
					notifyOn = Dispatchers.Main,
				) { state ->
					val message =
						when (state) {
							// not connected to a VM
							DebuggerConnectionState.DETACHED -> getString(R.string.debugger_state_not_connected)

							// connected, but not suspended
							DebuggerConnectionState.ATTACHED -> {
								viewModel.debugClient.clientOrNull?.let { client ->
									getString(
										R.string.debugger_state_connected,
										client.name,
										client.version,
									)
								}
							}

							// ----
							// No need to show any message for below states
							// the debugger UI will show the active threads, variables and call stack when the
							// VM is in one of these states

							// suspended, but not due to a breakpoint hit or step event
							DebuggerConnectionState.SUSPENDED -> null
							// suspended due to a breakpoint hit or step event
							DebuggerConnectionState.AWAITING_BREAKPOINT -> null
						}

					emptyStateViewModel.isEmpty.value =
						currentView == VIEW_DEBUGGER &&
						message != null
					emptyStateViewModel.emptyMessage.value = message
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
						emptyStateViewModel.isEmpty.value =
							currentView == VIEW_DEBUGGER &&
							descriptors.isEmpty()
						binding.debuggerContents.threadLayoutSelector.spinnerText.setAdapter(
							ThreadSelectorListAdapter(
								requireContext(),
								descriptors,
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

				ShizukuState.serviceStatus.collectLatest { currentStatus ->
					withContext(Dispatchers.IO) {
						onShizukuServiceStatusChange(currentStatus)
					}
				}
			}
		}

		emptyStateViewModel.emptyMessage.value = getString(R.string.debugger_state_not_connected)

		binding.debuggerContents.threadLayoutSelector.spinnerText.setOnItemClickListener { _, _, index, _ ->
			viewLifecycleScope.launch {
				viewModel.setSelectedThreadIndex(index)
			}
		}

		val mediator =
			TabLayoutMediator(
				binding.debuggerContents.tabs,
				binding.debuggerContents.pager,
			) { tab, position ->
				tab.text = tabs[position].first
			}

		binding.debuggerContents.pager.adapter = DebuggerPagerAdapter(this, tabs.map { it.second })
		mediator.attach()
	}

	override fun onFragmentLongPressed() {
		// TODO be defined
	}

	private fun onShizukuServiceStatusChange(status: ServiceStatus?) {
		logger.debug("Shizuku service status changed: {}", status)
		var newView = VIEW_DEBUGGER

		if (isAtLeastR() && status?.isRunning != true) {
			newView = VIEW_WADB_PAIRING
		}

		viewModel.currentView = newView
	}
}

class DebuggerPagerAdapter(
	fragment: DebuggerFragment,
	private val factories: List<() -> Fragment>,
) : FragmentStateAdapter(fragment) {
	override fun getItemCount(): Int = factories.size

	override fun createFragment(position: Int): Fragment = factories[position].invoke()
}

class ThreadSelectorListAdapter(
	context: Context,
	items: List<ThreadDescriptor?>,
) : ArrayAdapter<ThreadDescriptor?>(context, android.R.layout.simple_dropdown_item_1line, items) {
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

		view.isEnabled = isEnabled
		view.text = item.displayText()
		view.alpha = if (isEnabled) 1f else 0.5f
		return view
	}
}
