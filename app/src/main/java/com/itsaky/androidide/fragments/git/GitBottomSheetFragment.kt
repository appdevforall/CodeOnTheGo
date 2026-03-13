package com.itsaky.androidide.fragments.git

import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentGitBottomSheetBinding
import com.itsaky.androidide.fragments.git.adapter.GitFileChangeAdapter
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GitBottomSheetFragment : Fragment(R.layout.fragment_git_bottom_sheet) {

    private val viewModel: GitBottomSheetViewModel by activityViewModels()
    private lateinit var fileChangeAdapter: GitFileChangeAdapter

    private var _binding: FragmentGitBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGitBottomSheetBinding.bind(view)

        fileChangeAdapter = GitFileChangeAdapter(
            onFileClicked = { change ->
                // Show diff in a dialog when changed file is clicked
                val dialog = GitDiffViewerDialog.newInstance(change.path)
                dialog.show(childFragmentManager, "GitDiffViewerDialog")
            },
            onSelectionChanged = {
                validateCommitButton()
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = fileChangeAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gitStatus.collectLatest { status ->
                val allChanges =
                    status.staged + status.unstaged + status.untracked + status.conflicted

                if (allChanges.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                    binding.commitSection.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.commitSection.visibility = View.VISIBLE
                    fileChangeAdapter.submitList(allChanges)
                }
            }

        }

        setupCommitUI()

        binding.commitHistoryButton.setOnClickListener {
            val dialog = GitCommitHistoryDialog()
            dialog.show(childFragmentManager, "CommitHistoryDialog")
        }

    }

    private fun setupCommitUI() {
        binding.commitSummary.doAfterTextChanged { validateCommitButton() }
        binding.commitDescription.doAfterTextChanged { validateCommitButton() }

        binding.commitButton.setOnClickListener {
            val summary = binding.commitSummary.text?.toString()?.trim() ?: ""
            val description = binding.commitDescription.text?.toString()?.trim()

            if (summary.isNotEmpty() && fileChangeAdapter.selectedFiles.isNotEmpty()) {
                viewModel.commitChanges(
                    summary = summary,
                    description = description,
                    selectedPaths = fileChangeAdapter.selectedFiles.toList()
                )

                // Clear the inputs on commit attempt
                binding.commitSummary.text?.clear()
                binding.commitDescription.text?.clear()
                fileChangeAdapter.selectedFiles.clear()
            }
        }
    }

    private fun validateCommitButton() {
        val hasSummary = !binding.commitSummary.text.isNullOrBlank()
        val hasSelection = fileChangeAdapter.selectedFiles.isNotEmpty()
        binding.commitButton.isEnabled = hasSummary && hasSelection
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
