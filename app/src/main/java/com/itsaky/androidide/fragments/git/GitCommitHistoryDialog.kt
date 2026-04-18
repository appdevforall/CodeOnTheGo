package com.itsaky.androidide.fragments.git

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.DialogGitCommitHistoryBinding
import com.itsaky.androidide.databinding.DialogGitCredentialsBinding
import com.itsaky.androidide.fragments.git.adapter.GitCommitHistoryAdapter
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.git.core.models.CommitHistoryUiState
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class GitCommitHistoryDialog : DialogFragment() {

    private var _binding: DialogGitCommitHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GitBottomSheetViewModel by activityViewModel()
    private lateinit var commitHistoryAdapter: GitCommitHistoryAdapter
    private val credentialsManager: GitCredentialsManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_AndroidIDE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogGitCommitHistoryBinding.inflate(inflater, container, false)
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
            viewModel.commitHistory.collectLatest { state ->
                when (state) {
                    is CommitHistoryUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.emptyView.visibility = View.GONE
                        binding.rvCommitHistory.visibility = View.GONE
                    }
                    is CommitHistoryUiState.Empty -> {
                        binding.progressBar.visibility = View.GONE
                        binding.emptyView.visibility = View.VISIBLE
                        binding.emptyView.setText(R.string.no_commit_history)
                        binding.rvCommitHistory.visibility = View.GONE
                    }
                    is CommitHistoryUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.emptyView.visibility = View.VISIBLE
                        binding.emptyView.text = state.message ?: getString(R.string.unknown_error)
                        binding.rvCommitHistory.visibility = View.GONE
                    }
                    is CommitHistoryUiState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        binding.emptyView.visibility = View.GONE
                        binding.rvCommitHistory.visibility = View.VISIBLE
                        commitHistoryAdapter.submitList(state.commits)
                    }
                }
            }
        }

        setupPushUI()
    }

    private fun setupPushUI() {
        binding.btnPush.setOnClickListener {
            val username = credentialsManager.getUsername()
            val token = credentialsManager.getToken()
            if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                viewModel.push(username, token)
            } else {
                showCredentialsDialog()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.localCommitsCount.collectLatest { count ->
                binding.btnPush.visibility = if (count > 0) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pushState.collectLatest { state ->
                when (state) {
                    is GitBottomSheetViewModel.PushUiState.Idle -> {
                        binding.btnPush.isEnabled = true
                        binding.btnPush.text = getString(R.string.push)
                        binding.pushProgress.visibility = View.GONE
                    }
                    is GitBottomSheetViewModel.PushUiState.Pushing -> {
                        binding.btnPush.isEnabled = false
                        binding.pushProgress.visibility = View.VISIBLE
                    }
                    is GitBottomSheetViewModel.PushUiState.Success -> {
                        binding.btnPush.isEnabled = true
                        binding.pushProgress.visibility = View.GONE
                        flashSuccess(R.string.push_successful)
                        viewModel.resetPushState()
                        dismiss()
                    }
                    is GitBottomSheetViewModel.PushUiState.Error -> {
                        binding.btnPush.isEnabled = true
                        binding.pushProgress.visibility = View.GONE
                        val message = if (state.errorResId != null && state.errorResId != R.string.unknown_error) {
                            getString(state.errorResId)
                        } else {
                            state.message ?: getString(R.string.unknown_error)
                        }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.push_failed)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }

    }

    private fun showCredentialsDialog() {
        val dialogBinding = DialogGitCredentialsBinding.inflate(layoutInflater)

        dialogBinding.username.setText(credentialsManager.getUsername())
        dialogBinding.token.setText(credentialsManager.getToken())

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.git_credentials_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.push) { _, _ ->
                val username = dialogBinding.username.text?.toString()?.trim()
                val token = dialogBinding.token.text?.toString()?.trim()
                if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                    viewModel.push(username, token)
                }
            }
            .setNeutralButton(R.string.git_credentials_clear) { _, _ ->
                credentialsManager.clearCredentials()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
