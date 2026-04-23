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
import com.itsaky.androidide.fragments.git.adapter.GitCommitHistoryAdapter
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.git.core.models.CommitHistoryUiState
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.utils.applyLongPressRecursively
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.onLongPress
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

        binding.root.applyLongPressRecursively(listOf(binding.btnPush, binding.rvCommitHistory)) { view ->
            TooltipManager.showIdeCategoryTooltip(
                context = view.context,
                anchorView = view,
                tag = TooltipTag.GIT_COMMIT_HISTORY
            )
            true
        }

        binding.rvCommitHistory.apply {
            layoutManager = linearLayoutManager
            addItemDecoration(dividerItemDecoration)
            adapter = commitHistoryAdapter
            onLongPress {
                TooltipManager.showIdeCategoryTooltip(
                    context = binding.root.context,
                    anchorView = binding.root,
                    tag = TooltipTag.GIT_COMMIT_HISTORY
                )
            }

        binding.btnBack.setOnClickListener {
            dismiss()
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
    }}

    private fun setupPushUI() {
        binding.btnPush.setOnClickListener {
            val username = credentialsManager.getUsername()
            val token = credentialsManager.getToken()
            if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                viewModel.push(username, token)
            } else {
                showGitCredentialsDialog(
                    credentialsManager = credentialsManager,
                    positiveButtonTextResId = R.string.push
                ) { user, accessToken ->
                    viewModel.push(user, accessToken)
                }}

        binding.btnPush.apply {
            setOnClickListener {
                val username = credentialsManager.getUsername()
                val token = credentialsManager.getToken()
                if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                    viewModel.push(username, token)
                } else {
                    showGitCredentialsDialog(
                        credentialsManager = credentialsManager,
                        positiveButtonTextResId = R.string.pull
                    ) { user, accessToken ->
                        viewModel.pull(user, accessToken)
                    }
                }
            }
            setOnLongClickListener { view ->
                TooltipManager.showIdeCategoryTooltip(
                    context = view.context,
                    anchorView = view,
                    tag = TooltipTag.GIT_PUSH
                )
                true
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
                        val dialog = MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.push_failed)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                        dialog.onLongPress {
                            TooltipManager.showIdeCategoryTooltip(
                                context = binding.root.context,
                                anchorView = binding.root,
                                tag = TooltipTag.GIT_DIALOG_PUSH_FAIL
                            )
                            true
                        }
                        dialog.show()
                    }
                }
            }
        }

    }}

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
