// In: com/itsaky/androidide/fragments/sidebar/GitCommitFragment.kt

package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.databinding.FragmentGitCommitBinding
import com.itsaky.androidide.projects.ProjectManagerImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git

class GitCommitFragment : Fragment() {

    private var _binding: FragmentGitCommitBinding? = null
    private val binding get() = _binding!!

    private lateinit var git: Git
    private lateinit var gitStatusAdapter: GitStatusAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGitCommitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val projectDir = ProjectManagerImpl.getInstance().projectDir
        if (projectDir == null) {
            Toast.makeText(requireContext(), "No project open", Toast.LENGTH_SHORT).show()
            return
        }

        // Initialize the Git instance
        git = Git.open(projectDir)

        // Load the changed files into the RecyclerView
        loadGitStatus()

        // **MODIFIED:** The commit logic now lives here
        binding.btnCommit.setOnClickListener {
            val stagedFiles = gitStatusAdapter.getStagedFiles()
            val message = binding.commitMessageInput.text.toString()

            if (message.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "Commit message cannot be empty.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (stagedFiles.isEmpty()) {
                Toast.makeText(requireContext(), "No files selected to commit.", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            performCommit(stagedFiles, message)
        }
    }

    private fun loadGitStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = git.status().call()
            val fileList = mutableListOf<GitFileStatus>()

            // Populate the list with all changed files
            status.untracked.forEach { fileList.add(GitFileStatus(it, "New")) }
            status.added.forEach { fileList.add(GitFileStatus(it, "Added")) }
            status.modified.forEach { fileList.add(GitFileStatus(it, "Modified")) }
            status.changed.forEach { fileList.add(GitFileStatus(it, "Changed")) }
            status.removed.forEach { fileList.add(GitFileStatus(it, "Deleted")) }

            // Update the UI on the main thread
            withContext(Dispatchers.Main) {
                gitStatusAdapter = GitStatusAdapter(fileList)
                binding.commitFilesRecyclerView.apply {
                    adapter = gitStatusAdapter
                    layoutManager = LinearLayoutManager(requireContext())
                }
            }
        }
    }

    private fun performCommit(filesToCommit: List<String>, message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Add each selected file to the index
                val addCommand = git.add()
                filesToCommit.forEach { addCommand.addFilepattern(it) }
                addCommand.call()

                // Now, commit the files that were just added
                git.commit().setMessage(message).call()

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Commit successful!", Toast.LENGTH_SHORT)
                        .show()
                    // Optionally, navigate back after commit
                    // findNavController().popBackStack()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Commit failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}