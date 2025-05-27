package com.itsaky.androidide.fragments.debug

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.itsaky.androidide.databinding.FragmentDebuggerBinding
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.Dispatchers

/**
 * @author Akash Yadav
 */
class DebuggerFragment :
    FragmentWithBinding<FragmentDebuggerBinding>(FragmentDebuggerBinding::inflate) {

    private val tabs = listOf(
        "Variables" to VariableListFragment(),
//        "Call stack" to CallStackFragment()
    )

    private val viewModel by viewModels<DebuggerViewModel>(ownerProducer = { requireActivity() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.observeLatestThreads(
            notifyOn = Dispatchers.Main
        ) { threads ->
            binding.threadLayoutSelector.spinnerText.setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    threads
                )
            )
        }

        viewModel.observeLatestSelectedThread(
            notifyOn = Dispatchers.Main
        ) { thread, index ->
            if (index >= 0) {
                binding.threadLayoutSelector.spinnerText.apply {
                    listSelection = index
                    setText(thread!!.getName(), false)
                }
            }
        }

        binding.threadLayoutSelector.spinnerText.setOnItemClickListener { _, _, index, _ ->
            viewModel.setSelectedThreadIndex(index)
        }

        viewModel.setThreads(DebuggerViewModel.demoThreads())
        viewModel.setSelectedThreadIndex(0)
        viewModel.setSelectedFrameIndex(0)

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
