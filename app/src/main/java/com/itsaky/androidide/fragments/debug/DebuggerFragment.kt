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
import com.itsaky.androidide.viewmodel.DebuggerConnectionState
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author Akash Yadav
 */
class DebuggerFragment : EmptyStateFragment<FragmentDebuggerBinding>(FragmentDebuggerBinding::inflate) {
	private lateinit var tabs: Array<Pair<String, Fragment>>
	private val viewModel by activityViewModels<DebuggerViewModel>()

	companion object {
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
					TAB_INDEX_VARIABLES -> getString(R.string.debugger_variables) to VariableListFragment()
					TAB_INDEX_CALL_STACK -> getString(R.string.debugger_call_stack) to CallStackFragment()
					else -> throw IllegalStateException("Unknown position: $position")
				}
			}

		emptyStateViewModel.isEmpty.observe(viewLifecycleOwner) { isEmpty ->
			if (isEmpty) {
				binding.threadLayoutSelector.spinnerText.clearListSelection()
			}
		}

		viewLifecycleScope.launch {
			viewModel.setThreads(emptyList())

			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
									getString(R.string.debugger_state_connected, client.name, client.version)
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

					emptyStateViewModel.isEmpty.value = message != null
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
						emptyStateViewModel.isEmpty.value = descriptors.isEmpty()
						binding.threadLayoutSelector.spinnerText.setAdapter(
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
							binding.threadLayoutSelector.spinnerText.apply {
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

		emptyStateViewModel.emptyMessage.value = getString(R.string.debugger_state_not_connected)

		binding.threadLayoutSelector.spinnerText.setOnItemClickListener { _, _, index, _ ->
			viewLifecycleScope.launch {
				viewModel.setSelectedThreadIndex(index)
			}
		}

		val mediator =
			TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
				tab.text = tabs[position].first
			}

		binding.pager.adapter = DebuggerPagerAdapter(this, tabs.map { it.second })
		mediator.attach()
	}
}

class DebuggerPagerAdapter(
	fragment: DebuggerFragment,
	private val fragments: List<Fragment>,
) : FragmentStateAdapter(fragment) {
	override fun getItemCount(): Int = fragments.size

	override fun createFragment(position: Int): Fragment = fragments[position]
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
