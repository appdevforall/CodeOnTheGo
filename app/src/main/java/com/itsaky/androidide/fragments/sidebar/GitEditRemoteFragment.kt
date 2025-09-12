package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentGitEditRemoteBinding
import com.itsaky.androidide.projects.ProjectManagerImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish

class GitEditRemoteFragment : Fragment(R.layout.fragment_git_edit_remote) {

    private var _binding: FragmentGitEditRemoteBinding? = null
    private val binding get() = _binding!!
    private val args: GitEditRemoteFragmentArgs by navArgs()
    private var originalRemoteName: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGitEditRemoteBinding.bind(view)

        originalRemoteName = args.remoteName

        if (originalRemoteName != null) {
            loadRemoteDetails(originalRemoteName!!)
        } else {
            binding.btnDeleteRemote.isVisible = false
        }

        binding.btnSaveRemote.setOnClickListener { saveRemote() }
        binding.btnDeleteRemote.setOnClickListener { /* Add confirmation dialog and delete logic */ }
    }

    private fun loadRemoteDetails(remoteName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // ... (Fetch remote details and populate UI)
        }
    }

    private fun saveRemote() {
        val name = binding.remoteNameInput.text.toString().trim()
        val url = binding.remoteUrlInput.text.toString().trim()

        if (name.isEmpty() || url.isEmpty()) {
            Snackbar.make(binding.root, "Name and URL cannot be empty.", Snackbar.LENGTH_SHORT)
                .show()
            return
        }

        binding.loadingProgressBar.isVisible = true
        binding.btnSaveRemote.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val projectDir = ProjectManagerImpl.getInstance().projectDir
                    ?: throw IllegalStateException("Project not open")
                val git = Git.open(projectDir)

                // If editing, remove the old one first
                if (originalRemoteName != null) {
                    git.remoteRemove().setRemoteName(originalRemoteName).call()
                }

                // Add the new or updated remote
                git.remoteAdd().setName(name).setUri(URIish().setRawPath(url)).call()

                // If checked, verify by listing remote heads
                if (binding.fetchCheckBox.isChecked) {
                    git.lsRemote().setRemote(name).setHeads(true).call()
                }

                withContext(Dispatchers.Main) {
                    findNavController().popBackStack()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingProgressBar.isVisible = false
                    binding.btnSaveRemote.isEnabled = true
                    val message = e.cause?.message ?: e.message
                    Snackbar.make(binding.root, "Error: $message", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}