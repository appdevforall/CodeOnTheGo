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
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentDebuggerBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadState
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
class DebuggerFragment :
    EmptyStateFragment<FragmentDebuggerBinding>(FragmentDebuggerBinding::inflate) {

    private lateinit var tabs: Array<Pair<String, Fragment>>
    private val viewModel by activityViewModels<DebuggerViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabs = Array(2) { position ->
            when (position) {
                0 -> getString(R.string.debugger_variables) to VariableListFragment()
                1 -> getString(R.string.debugger_call_stack) to CallStackFragment()
                else -> throw IllegalStateException("Unknown position: $0")
            }
        }

        emptyStateViewModel.emptyMessage.value = getString(R.string.debugger_msg_not_connected)
        emptyStateViewModel.isEmpty.observe(viewLifecycleOwner) { isEmpty ->
            if (isEmpty) {
                binding.threadLayoutSelector.spinnerText.clearListSelection()
            }
        }

        viewModel.observeLatestThreads(
            notifyOn = Dispatchers.IO
        ) { threads ->
            coroutineScope {
                val descriptors = threads.map { thread ->
                    async {
                        thread.resolve()
                    }
                }.awaitAll()

                withContext(Dispatchers.Main) {
                    emptyStateViewModel.isEmpty.value = descriptors.isEmpty()
                    binding.threadLayoutSelector.spinnerText.setAdapter(
                        ThreadSelectorListAdapter(
                            requireContext(),
                            descriptors
                        )
                    )
                }
            }
        }

        viewModel.observeLatestSelectedThread(
            notifyOn = Dispatchers.IO
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
                            false
                        )
                    }
                }
            }
        }

        binding.threadLayoutSelector.spinnerText.setOnItemClickListener { _, _, index, _ ->
            viewLifecycleScope.launch {
                viewModel.setSelectedThreadIndex(index)
            }
        }

        viewLifecycleScope.launch {
            viewModel.setThreads(emptyList())
        }

        val mediator = TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = tabs[position].first
        }

        binding.pager.adapter = DebuggerPagerAdapter(this, tabs.map { it.second })
        mediator.attach()
    }
}

class DebuggerPagerAdapter(
    fragment: DebuggerFragment, private val fragments: List<Fragment>
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = fragments.size
    override fun createFragment(position: Int): Fragment = fragments[position]
}

class ThreadSelectorListAdapter(
    context: Context, items: List<ThreadDescriptor?>
) : ArrayAdapter<ThreadDescriptor?>(context, android.R.layout.simple_dropdown_item_1line, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(this.context)
        val view = (convertView ?: inflater.inflate(
            android.R.layout.simple_dropdown_item_1line, parent, false
        )) as TextView

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

