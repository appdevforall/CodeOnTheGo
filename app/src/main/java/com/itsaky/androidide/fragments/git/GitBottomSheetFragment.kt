package com.itsaky.androidide.fragments.git

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.databinding.FragmentGitBottomSheetBinding
import com.itsaky.androidide.fragments.git.adapter.GitFileChangeAdapter
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
            combine(
                viewModel.isGitRepository,
                viewModel.gitStatus
            ) { isRepo, status ->
                val allChanges = status.staged + status.unstaged + status.untracked + status.conflicted

                when {
                    !isRepo -> binding.apply {
                        emptyView.visibility = View.VISIBLE
                        emptyView.text = getString(R.string.not_a_git_repo)
                        recyclerView.visibility = View.GONE
                        commitSection.visibility = View.GONE
                        authorWarning.visibility = View.GONE
                        commitHistoryButton.visibility = View.GONE
                    }
                    allChanges.isEmpty() -> binding.apply {
                        emptyView.visibility = View.VISIBLE
                        emptyView.text = getString(R.string.no_uncommitted_changes)
                        recyclerView.visibility = View.GONE
                        commitSection.visibility = View.GONE
                        authorWarning.visibility = View.GONE
                        commitHistoryButton.visibility = View.VISIBLE
                    }
                    else -> {
                        binding.apply {
                            emptyView.visibility = View.GONE
                            recyclerView.visibility = View.VISIBLE
                            commitSection.visibility = View.VISIBLE
                            authorWarning.visibility = if (hasAuthorInfo()) View.GONE else View.VISIBLE
                            commitHistoryButton.visibility = View.VISIBLE
                        }
                        fileChangeAdapter.submitList(allChanges)
                    }
                }
            }.collectLatest { }
        }

        setupCommitUI()

        binding.commitHistoryButton.setOnClickListener {
            val dialog = GitCommitHistoryDialog()
            dialog.show(childFragmentManager, "CommitHistoryDialog")
        }

    }

    override fun onResume() {
        super.onResume()
        updateAuthorUI()
    }

    private fun updateAuthorUI() {
        val hasAuthor = hasAuthorInfo()
        val allChanges = viewModel.gitStatus.value.staged + viewModel.gitStatus.value.unstaged + viewModel.gitStatus.value.untracked + viewModel.gitStatus.value.conflicted
        binding.authorWarning.visibility = if (!hasAuthor && allChanges.isNotEmpty()) View.VISIBLE else View.GONE
        validateCommitButton()
    }

    private fun hasAuthorInfo(): Boolean {
        return !GitPreferences.userName.isNullOrBlank() && !GitPreferences.userEmail.isNullOrBlank()
    }

    private fun setupCommitUI() {
        binding.commitSummary.doAfterTextChanged { validateCommitButton() }
        binding.commitDescription.doAfterTextChanged { validateCommitButton() }

        binding.authorAvatar.setOnClickListener {
            showAuthorPopup()
        }

        binding.commitButton.setOnClickListener {
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

    private fun showAuthorPopup() {
        val name = GitPreferences.userName.orEmpty().ifBlank { getString(R.string.author_not_set) }
        val email = GitPreferences.userEmail.orEmpty().ifBlank { getString(R.string.author_not_set) }
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
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun validateCommitButton() {
        val hasSummary = !binding.commitSummary.text.isNullOrBlank()
        val hasSelection = fileChangeAdapter.selectedFiles.isNotEmpty()
        val hasAuthor = hasAuthorInfo()
        binding.commitButton.isEnabled = hasSummary && hasSelection && hasAuthor
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
