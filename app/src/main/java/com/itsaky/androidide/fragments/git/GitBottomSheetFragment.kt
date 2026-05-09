package com.itsaky.androidide.fragments.git

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.databinding.FragmentGitBottomSheetBinding
import com.itsaky.androidide.fragments.git.adapter.GitFileChangeAdapter
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.git.core.models.ChangeType
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.interfaces.IEditorHandler
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.onLongPress
import com.itsaky.androidide.viewmodel.BottomSheetViewModel
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel.PullUiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.File

class GitBottomSheetFragment : Fragment(R.layout.fragment_git_bottom_sheet) {

    private val viewModel: GitBottomSheetViewModel by activityViewModel()
    private val bottomSheetViewModel: BottomSheetViewModel by activityViewModel()
    private lateinit var fileChangeAdapter: GitFileChangeAdapter
    private lateinit var credentialsManager: GitCredentialsManager

    private var _binding: FragmentGitBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGitBottomSheetBinding.bind(view)
        credentialsManager = GitCredentialsManager(requireContext())

        fileChangeAdapter = GitFileChangeAdapter(
            onFileClicked = { change ->
                when (change.type) {
                    ChangeType.CONFLICTED -> {
                        val activity = requireActivity()
                        if (activity is EditorHandlerActivity) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val repo = viewModel.currentRepository
                                repo?.let {
                                    activity.checkForExternalFileChanges(force = true)
                                    activity.openFile(File(repo.rootDir, change.path))
                                    bottomSheetViewModel.setSheetState(BottomSheetBehavior.STATE_COLLAPSED)
                                }
                            }
                        }
                    }

                    else -> {
                        val dialog = GitDiffViewerDialog.newInstance(change.path)
                        dialog.show(childFragmentManager, "GitDiffViewerDialog")
                    }
                }
            },
            onSelectionChanged = {
                validateCommitButton()
            },
            onResolveConflict = { change ->
                viewModel.resolveConflict(change.path)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = fileChangeAdapter
        binding.recyclerView.onLongPress { _ ->
            TooltipManager.showIdeCategoryTooltip(
                context = requireContext(),
                anchorView = binding.recyclerView,
                tag = TooltipTag.PROJECT_GIT_FILES,
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.currentBranch.collectLatest { branchName ->
                    if (branchName != null) {
                        binding.tvBranchName.visibility = View.VISIBLE
                        binding.tvBranchName.text =
                            getString(R.string.current_branch_name, branchName)
                    } else {
                        binding.tvBranchName.visibility = View.GONE
                    }
                }
            }

            combine(
                viewModel.isGitRepository,
                viewModel.gitStatus
            ) { isRepo, status ->
                val allChanges =
                    status.staged + status.unstaged + status.untracked + status.conflicted

                when {
                    !isRepo -> binding.apply {
                        emptyView.visibility = View.VISIBLE
                        emptyView.text = getString(R.string.not_a_git_repo)
                        recyclerView.visibility = View.GONE
                        commitSection.visibility = View.GONE
                        authorWarning.visibility = View.GONE
                        commitHistoryButton.visibility = View.GONE
                        btnAbortMerge.visibility = View.GONE
                    }

                    allChanges.isEmpty() -> binding.apply {
                        emptyView.visibility = View.VISIBLE
                        emptyView.text = getString(R.string.no_uncommitted_changes)
                        recyclerView.visibility = View.GONE
                        commitSection.visibility = View.GONE
                        authorWarning.visibility = View.GONE
                        commitHistoryButton.visibility = View.VISIBLE
                        btnAbortMerge.visibility = View.GONE
                    }

                    else -> {
                        binding.apply {
                            emptyView.visibility = View.GONE
                            recyclerView.visibility = View.VISIBLE
                            commitSection.visibility = View.VISIBLE
                            authorWarning.visibility =
                                if (hasAuthorInfo()) View.GONE else View.VISIBLE
                            commitHistoryButton.visibility = View.VISIBLE
                            btnAbortMerge.visibility =
                                if (status.isMerging) View.VISIBLE else View.GONE
                        }
                        fileChangeAdapter.submitList(allChanges)
                    }
                }
            }.collectLatest { }
        }

        setupCommitUI()

        binding.commitHistoryButton.apply {
            setOnClickListener {
                val dialog = GitCommitHistoryDialog()
                dialog.show(childFragmentManager, "CommitHistoryDialog")
            }
            setTooltipOnView(TooltipTag.PROJECT_GIT_COMMIT_HISTORY)
        }

        setupPullUI()
    }

    override fun onResume() {
        super.onResume()
        updateAuthorUI()
    }

    private fun updateAuthorUI() {
        val hasAuthor = hasAuthorInfo()
        val allChanges =
            viewModel.gitStatus.value.staged + viewModel.gitStatus.value.unstaged + viewModel.gitStatus.value.untracked + viewModel.gitStatus.value.conflicted
        binding.authorWarning.visibility =
            if (!hasAuthor && allChanges.isNotEmpty()) View.VISIBLE else View.GONE
        validateCommitButton()
    }

    private fun hasAuthorInfo(): Boolean {
        return !GitPreferences.userName.isNullOrBlank() && !GitPreferences.userEmail.isNullOrBlank()
    }

    private fun setupCommitUI() {
        binding.commitSummary.doAfterTextChanged { validateCommitButton() }
        binding.commitDescription.doAfterTextChanged { validateCommitButton() }

        binding.btnAbortMerge.apply {
            setOnClickListener {
                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.abort_merge)
                    .setMessage(R.string.confirm_abort_merge)
                    .setPositiveButton(R.string.abort_merge) { _, _ ->
                        viewModel.abortMerge {
                            val activity = requireActivity()
                            if (activity is EditorHandlerActivity) {
                                activity.checkForExternalFileChanges(force = true)
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                dialog.setTooltipOnDialog(TooltipTag.GIT_DIALOG_ABORT_MERGE)
                dialog.show()
            }
            setTooltipOnView(TooltipTag.PROJECT_GIT_ABORT)
        }

        binding.authorAvatar.apply {
            setOnClickListener { showAuthorPopup() }
            setTooltipOnView(TooltipTag.PROJECT_GIT_ID)
        }

        binding.commitButton.apply {
            setOnClickListener {
                checkUnsavedChangesAndProceed {
                    val summary = binding.commitSummary.text?.toString()?.trim() ?: ""
                    val description = binding.commitDescription.text?.toString()?.trim()

                    if (summary.isNotEmpty() && fileChangeAdapter.selectedFiles.isNotEmpty() && hasAuthorInfo()) {
                        viewModel.commitChanges(
                            summary = summary,
                            description = description,
                            selectedPaths = fileChangeAdapter.selectedFiles.toList()
                        ) {
                            // Clear the inputs on successful commit
                            binding.commitSummary.text?.clear()
                            binding.commitDescription.text?.clear()
                            fileChangeAdapter.selectedFiles.clear()
                        }
                    }
                }
            }
            setTooltipOnView(TooltipTag.PROJECT_GIT_COMMIT)
        }
    }

    private fun showAuthorPopup() {
        val name = GitPreferences.userName.orEmpty().ifBlank { getString(R.string.author_not_set) }
        val email =
            GitPreferences.userEmail.orEmpty().ifBlank { getString(R.string.author_not_set) }
        val message = getString(R.string.git_committing_as, name) + "\n" +
                getString(R.string.git_committing_email, email) + "\n\n" +
                getString(R.string.git_update_config_in_preferences)

        val spannable = SpannableString(message)
        val preferencesText = getString(R.string.git_update_config_in_preferences)
        val startIndex = message.indexOf(preferencesText)

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.idepref_git_author_title)
            .setMessage(spannable)
            .setPositiveButton(android.R.string.ok, null)

        val dialog = builder.create()

        if (startIndex != -1) {
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val intent = Intent(
                            requireContext(),
                            PreferencesActivity::class.java
                        )
                        dialog.dismiss()
                        startActivity(intent)
                    }
                },
                startIndex,
                startIndex + preferencesText.length,
                SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun validateCommitButton() {
        val hasSummary = !binding.commitSummary.text.isNullOrBlank()
        val hasSelection = fileChangeAdapter.selectedFiles.isNotEmpty()
        val hasAuthor = hasAuthorInfo()
        binding.commitButton.isEnabled = hasSummary && hasSelection && hasAuthor
    }

    private fun setupPullUI() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isGitRepository.collectLatest { isRepo ->
                binding.btnPull.visibility = if (isRepo) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pullState.collectLatest { state ->
                when (state) {
                    is PullUiState.Idle -> {
                        binding.btnPull.isEnabled = true
                        binding.pullProgress.visibility = View.GONE
                    }

                    is PullUiState.Pulling -> {
                        binding.btnPull.isEnabled = false
                        binding.pullProgress.visibility = View.VISIBLE
                    }

                    is PullUiState.Success -> {
                        binding.btnPull.isEnabled = true
                        binding.pullProgress.visibility = View.GONE
                        flashSuccess(R.string.pull_successful)
                        viewModel.resetPullState()
                        refreshEditorContent()
                    }

                    is PullUiState.Conflicts -> {
                        binding.btnPull.isEnabled = true
                        binding.pullProgress.visibility = View.GONE
                        val message = state.message ?: getString(R.string.info_merge_conflicts)
                        val dialog = MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.merge_conflicts))
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                        dialog.setTooltipOnDialog(TooltipTag.GIT_DIALOG_MERGE_CONFLICTS)
                        dialog.show()
                        viewModel.resetPullState()
                        refreshEditorContent()
                    }

                    is PullUiState.Error -> {
                        binding.btnPull.isEnabled = true
                        binding.pullProgress.visibility = View.GONE
                        val message =
                            state.message ?: state.errorResId?.let { resId ->
                                if (state.errorArgs != null) getString(
                                    resId,
                                    *state.errorArgs.toTypedArray()
                                ) else getString(resId)
                            }
                        val dialog = MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.pull_failed)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                        dialog.setTooltipOnDialog(TooltipTag.GIT_DIALOG_PULL_FAIL)
                        dialog.show()
                    }
                }
            }
        }

        binding.btnPull.apply {
            setOnClickListener {
                checkUnsavedChangesAndProceed {
                    val username = credentialsManager.getUsername()
                    val token = credentialsManager.getToken()
                    if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                        viewModel.pull(username, token)
                    } else {
                        showGitCredentialsDialog(
                            credentialsManager = credentialsManager,
                            positiveButtonTextResId = R.string.pull
                        ) { user, accessToken ->
                            viewModel.pull(user, accessToken)
                        }
                    }
                }
            }
            setTooltipOnView(TooltipTag.GIT_PULL)
        }
    }

    private fun refreshEditorContent(force: Boolean = false) {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.checkForExternalFileChanges(force)
        }
    }

    private fun checkUnsavedChangesAndProceed(action: () -> Unit) {
        val handler = requireActivity() as? IEditorHandler
        if (handler?.areFilesModified() == true) {
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.title_files_unsaved)
                .setMessage(R.string.msg_save_before_git_action)
                .setPositiveButton(R.string.save_before_git_action) { _, _ ->
                    handler.saveAllAsync { action() }
                }
                .setNegativeButton(R.string.no_save_before_git_action) { _, _ ->
                    action()
                }
                .setNeutralButton(android.R.string.cancel, null)
                .create()
            dialog.setTooltipOnDialog(TooltipTag.GIT_DIALOG_SAVE)
            dialog.show()
        } else {
            action()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun AlertDialog.setTooltipOnDialog(tag: String) {
        onLongPress { view ->
            TooltipManager.showIdeCategoryTooltip(
                context = view.context,
                anchorView = view,
                tag = tag
            )
            true
        }
    }

    private fun View.setTooltipOnView(tag: String) {
        setOnLongClickListener { view ->
            TooltipManager.showIdeCategoryTooltip(
                context = view.context,
                anchorView = view,
                tag = tag
            )
            true
        }
    }
}
