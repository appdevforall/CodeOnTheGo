package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentGitBinding
import com.itsaky.androidide.fragments.EmptyStateFragment
import com.itsaky.androidide.git.GitInitTask
import com.itsaky.androidide.git.GitPullTask
import com.itsaky.androidide.git.GitPushTask
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.viewmodel.GitViewModel
import java.io.File

class GitFragment :
    EmptyStateFragment<FragmentGitBinding>(FragmentGitBinding::inflate) {

    private val gitViewModel by viewModels<GitViewModel>(
        ownerProducer = { requireActivity() }
    )

    override fun onResume() {
        super.onResume()
        updateGitButtonVisibility()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyStateViewModel.emptyMessage.value = "No git actions yet"
        emptyStateViewModel.isEmpty.value = false

        // --- Other button listeners remain the same ---
        binding.btnManageRemotes.setOnClickListener {
            findNavController().navigate(R.id.action_gitFragment_to_gitRemotesListFragment)
        }
        binding.btnGitCommit.setOnClickListener {
            findNavController().navigate(R.id.action_gitFragment_to_gitCommitFragment)
        }
        binding.btnGitPush.setOnClickListener {
            GitPushTask.push(requireContext())
        }
        binding.btnGitPull.setOnClickListener {
            GitPullTask.pull(requireContext())
        }
        binding.btnGitLog.setOnClickListener {
            findNavController().navigate(R.id.action_gitFragment_to_gitCommitListFragment)
        }

        // --- Updated Git Init button listener ---
        binding.btnGitInit.setOnClickListener {
            val userName = binding.gitUserNameInput.text?.toString()
            val userEmail = binding.gitUserEmailInput.text?.toString()

            // Pass the values to the init function.
            // .takeIf { it.isNotBlank() } conveniently converts blank strings to null.
            GitInitTask.init(
                context = requireContext(),
                userName = userName?.takeIf { it.isNotBlank() },
                userEmail = userEmail?.takeIf { it.isNotBlank() }
            )

            // Refresh the UI after a short delay to allow the async task to complete
            view.postDelayed({
                updateGitButtonVisibility()
            }, 1500)
        }
    }

    private fun updateGitButtonVisibility() {
        val projectDir = ProjectManagerImpl.getInstance().projectDir

        val isGitRepo = if (projectDir != null) {
            val gitDir = File(projectDir, ".git")
            gitDir.exists() && gitDir.isDirectory
        } else {
            false
        }

        // Toggle visibility of the entire layouts based on repo status
        binding.initLayout.isVisible = !isGitRepo
        binding.actionsLayout.isVisible = isGitRepo
    }
}