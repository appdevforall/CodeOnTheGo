package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.ContextChipAdapter
import com.itsaky.androidide.databinding.FragmentContextSelectionBinding

class ContextSelectionFragment : Fragment(R.layout.fragment_context_selection) {

    private var _binding: FragmentContextSelectionBinding? = null
    private val binding get() = _binding!!

    private val selectedContextItems = mutableSetOf<String>()
    private lateinit var chipAdapter: ContextChipAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContextSelectionBinding.bind(view)

        setupRecyclerView()
        setupListeners()
        updateSelectedChips()
    }
    private fun setupRecyclerView() {
        chipAdapter = ContextChipAdapter(mutableListOf()) { itemToRemove ->
            // This lambda is called when a chip's close icon is clicked
            toggleSelection(itemToRemove)
        }
        binding.selectedContextRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = chipAdapter
        }
    }
    private fun setupListeners() {
        binding.contextToolbar.setNavigationOnClickListener {
            findNavController().popBackStack() // Go back
        }

        // --- Mock Click Listeners ---
        binding.contextFile1.setOnClickListener { toggleSelection(binding.contextFile1.text.toString()) }
        binding.contextFile2.setOnClickListener { toggleSelection(binding.contextFile2.text.toString()) }
        binding.contextWebSearch.setOnClickListener { toggleSelection(binding.contextWebSearch.text.toString()) }
        binding.contextGitStatus.setOnClickListener { toggleSelection(binding.contextGitStatus.text.toString()) }

        binding.btnCancelContext.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnConfirmContext.setOnClickListener {
            setFragmentResult("context_selection_request", Bundle().apply {
                putStringArrayList("selected_context", ArrayList(selectedContextItems))
            })
            findNavController().popBackStack()
        }
    }

    private fun toggleSelection(itemText: String) {
        if (selectedContextItems.contains(itemText)) {
            selectedContextItems.remove(itemText)
        } else {
            selectedContextItems.add(itemText)
        }
        updateSelectedChips()
    }
    private fun updateSelectedChips() {
        binding.selectedContextRecyclerView.visibility = if (selectedContextItems.isEmpty()) View.GONE else View.VISIBLE
        // Update the adapter with the new set of items
        chipAdapter.updateData(selectedContextItems)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}