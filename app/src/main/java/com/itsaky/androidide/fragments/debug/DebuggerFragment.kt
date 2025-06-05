package com.itsaky.androidide.fragments.debug

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentDebuggerBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                val labels = threads.map { thread ->
                    async {
                        thread.resolve().displayText()
                    }
                }.awaitAll()

                withContext(Dispatchers.Main) {
                    emptyStateViewModel.isEmpty.value = labels.isEmpty()
                    binding.threadLayoutSelector.spinnerText.setAdapter(
                        ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            labels
                        )
                    )
                }
            }
        }

        viewModel.observeLatestSelectedThread(
            notifyOn = Dispatchers.IO
        ) { thread, index ->
            if (index >= 0) {
                val descriptor = thread!!.resolve()
                withContext(Dispatchers.Main) {
                    binding.threadLayoutSelector.spinnerText.apply {
                        listSelection = index
                        // noinspection SetTextI18n
                        setText(descriptor.displayText(), false)
                    }
                }
            }
        }

        binding.threadLayoutSelector.spinnerText.setOnItemClickListener { _, _, index, _ ->
            viewModel.setSelectedThreadIndex(index)
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
    fragment: DebuggerFragment,
    private val fragments: List<Fragment>
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]
}
