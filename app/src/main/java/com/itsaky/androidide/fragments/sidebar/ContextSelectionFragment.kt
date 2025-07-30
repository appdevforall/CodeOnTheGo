package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.ContextChipAdapter
import com.itsaky.androidide.databinding.FragmentContextSelectionBinding

class ContextSelectionFragment : Fragment(R.layout.fragment_context_selection) {

    private var _binding: FragmentContextSelectionBinding? = null
    private val binding get() = _binding!!

    private val selectedContextItems = mutableSetOf<String>()
    private lateinit var chipAdapter: ContextChipAdapter

    // Helper data class to link UI elements together
    private data class ContextItemViews(val layout: View, val checkmark: ImageView)

    private lateinit var contextItemViewMap: Map<String, ContextItemViews>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentContextSelectionBinding.bind(view)

        initializeViewMap()
        setupRecyclerView()
        setupListeners()
        updateSelectionVisuals()
        updateSelectedChips()
    }

    // Create a map to easily find the views for a given context item
    private fun initializeViewMap() {
        contextItemViewMap = mapOf(
            binding.contextFile1Text.text.toString() to ContextItemViews(
                binding.contextFile1Layout,
                binding.contextFile1Check
            ),
            binding.contextFile2Text.text.toString() to ContextItemViews(
                binding.contextFile2Layout,
                binding.contextFile2Check
            ),
            binding.contextWebSearchText.text.toString() to ContextItemViews(
                binding.contextWebSearchLayout,
                binding.contextWebSearchCheck
            ),
            binding.contextGitStatusText.text.toString() to ContextItemViews(
                binding.contextGitStatusLayout,
                binding.contextGitStatusCheck
            )
        )
    }

    private fun setupRecyclerView() {
        chipAdapter = ContextChipAdapter(mutableListOf()) { itemToRemove ->
            toggleSelection(itemToRemove)
        }
        binding.selectedContextRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = chipAdapter
        }
    }

    private fun setupListeners() {
        binding.contextToolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Set click listeners for each item's layout
        contextItemViewMap.forEach { (text, views) ->
            views.layout.setOnClickListener {
                toggleSelection(text)
            }
        }

        binding.btnCancelContext.setOnClickListener { findNavController().popBackStack() }

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
        updateSelectionVisuals() // Update the checkmarks whenever selection changes
    }

    // This new function updates the visibility of the checkmarks
    private fun updateSelectionVisuals() {
        contextItemViewMap.forEach { (text, views) ->
            views.checkmark.isVisible = selectedContextItems.contains(text)
        }
    }

    private fun updateSelectedChips() {
        binding.selectedContextRecyclerView.visibility = if (selectedContextItems.isEmpty()) View.GONE else View.VISIBLE
        chipAdapter.updateData(selectedContextItems)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}