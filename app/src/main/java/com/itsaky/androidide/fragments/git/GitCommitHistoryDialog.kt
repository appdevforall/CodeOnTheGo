package com.itsaky.androidide.fragments.git

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.DialogGitCommitHistoryBinding
import com.itsaky.androidide.fragments.git.adapter.GitCommitHistoryAdapter
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GitCommitHistoryDialog : DialogFragment() {

    private lateinit var binding: DialogGitCommitHistoryBinding
    private val viewModel: GitBottomSheetViewModel by activityViewModels()
    private lateinit var commitHistoryAdapter: GitCommitHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_AndroidIDE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogGitCommitHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        commitHistoryAdapter = GitCommitHistoryAdapter()
        val linearLayoutManager = LinearLayoutManager(requireContext())
        val dividerItemDecoration = DividerItemDecoration(
            binding.rvCommitHistory.context,
            linearLayoutManager.orientation
        )
        binding.rvCommitHistory.apply {
            layoutManager = linearLayoutManager
            addItemDecoration(dividerItemDecoration)
            adapter = commitHistoryAdapter
        }

        viewModel.getCommitHistoryList()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.commitHistory.collectLatest { history ->
                if (history.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.rvCommitHistory.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.rvCommitHistory.visibility = View.VISIBLE
                    commitHistoryAdapter.submitList(history)
                }
            }
        }
    }

}
