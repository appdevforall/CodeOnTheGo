/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

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
        // Update the UI every time the activity is shown
        updateGitButtonVisibility()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyStateViewModel.emptyMessage.value = "No git actions yet"
        emptyStateViewModel.isEmpty.value = false

        binding.btnManageRemotes.setOnClickListener {
            findNavController().navigate(R.id.action_gitFragment_to_gitRemotesListFragment)
        }
        // Set up the Commit button
        binding.btnGitCommit.setOnClickListener {
            // Use the NavController to navigate via the action defined in the graph
            findNavController().navigate(R.id.action_gitFragment_to_gitCommitFragment)
        }

        // Set up the Push button
        binding.btnGitPush.setOnClickListener {
            // This runs the push task and shows progress via GitProgressMonitor.
            GitPushTask.push(requireContext())
        }

        // Set up the Pull button
        binding.btnGitPull.setOnClickListener {
            GitPullTask.pull(requireContext())
        }
        binding.btnGitInit.setOnClickListener {
            GitInitTask.init(requireContext())
            // After attempting to init, refresh the button visibility
            updateGitButtonVisibility()
        }

        binding.btnGitLog.setOnClickListener {
            findNavController().navigate(R.id.action_gitFragment_to_gitCommitListFragment)
        }
    }

    private fun updateGitButtonVisibility() {
        val projectDir = ProjectManagerImpl.getInstance().projectDir

        val gitDir = File(projectDir, ".git")
        val isGitRepo = gitDir.exists()

        // Show "Init" only if it's NOT a git repo
        binding.btnGitInit.isVisible = !isGitRepo

        // Show other Git actions only if it IS a git repo
        binding.btnGitCommit.isVisible = isGitRepo
        binding.btnGitPush.isVisible = isGitRepo
        binding.btnGitPull.isVisible = isGitRepo
    }
}