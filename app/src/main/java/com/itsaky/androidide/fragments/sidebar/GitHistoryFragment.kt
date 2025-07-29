package com.itsaky.androidide.fragments.sidebar;

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentGitCommitListBinding
import com.itsaky.androidide.projects.ProjectManagerImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit

class GitHistoryFragment : Fragment(R.layout.fragment_git_commit_list) {
    // Basic implementation using ViewBinding
    private var _binding: FragmentGitCommitListBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGitCommitListBinding.bind(view)

        loadCommits()

    }

    private fun loadCommits() {
        lifecycleScope.launch(Dispatchers.IO) {
            val projectDir = ProjectManagerImpl.getInstance().projectDir
            val git = Git.open(projectDir)
            val commits = git.log().all().call().toList()

            withContext(Dispatchers.Main) {
                setupRecyclerView(commits)
            }
        }
    }

    private fun setupRecyclerView(remotes: List<RevCommit>) {
        val adapter = GitHistoryAdapter(remotes) { remote ->
//            val action =
//                GitRemotesListFragmentDirections.actionGitRemotesListFragmentToGitEditRemoteFragment(
//                    remote.name
//                )
//            findNavController().navigate(action)
        }
        binding.commitsRecyclerView.adapter = adapter
        binding.commitsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}