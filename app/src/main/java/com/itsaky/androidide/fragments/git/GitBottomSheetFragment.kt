package com.itsaky.androidide.fragments.git

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentGitBottomSheetBinding
import com.itsaky.androidide.fragments.git.adapter.GitFileChangeAdapter
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

class GitBottomSheetFragment : Fragment(R.layout.fragment_git_bottom_sheet) {

    private val viewModel: GitBottomSheetViewModel by viewModel()
    private lateinit var adapter: GitFileChangeAdapter

    private var _binding: FragmentGitBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGitBottomSheetBinding.bind(view)

        adapter = GitFileChangeAdapter(onFileClicked = { change ->
            val file = File(viewModel.currentRepository?.rootDir, change.path)
            viewLifecycleOwner.lifecycleScope.launch {
                val diffText = viewModel.currentRepository?.getDiff(file)
                    ?: getString(R.string.unable_to_load_diff)
                val dialog = GitDiffViewerDialog.newInstance(change.path, diffText)
                // Show diff in a dialog when changed file is clicked
                dialog.show(childFragmentManager, "GitDiffViewerDialog")
            }
        })

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gitStatus.collectLatest { status ->
                val allChanges = status.staged + status.unstaged + status.untracked + status.conflicted

                if (allChanges.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    adapter.submitList(allChanges)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
