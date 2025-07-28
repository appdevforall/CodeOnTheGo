package com.itsaky.androidide.fragments.sidebar;

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.FragmentGitRemotesListBinding
import com.itsaky.androidide.projects.ProjectManagerImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git

class GitRemotesListFragment : Fragment(R.layout.fragment_git_remotes_list) {
    // Basic implementation using ViewBinding
    private var _binding: FragmentGitRemotesListBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGitRemotesListBinding.bind(view)

        loadRemotes()

        binding.fabAddRemote.setOnClickListener {
            findNavController().navigate(R.id.action_gitRemotesListFragment_to_gitEditRemoteFragment)
        }
    }

    private fun loadRemotes() {
        lifecycleScope.launch(Dispatchers.IO) {
            val projectDir = ProjectManagerImpl.getInstance().projectDir ?: return@launch
            val git = Git.open(projectDir)
            val remotes = git.remoteList().call()
                .map { GitRemote(it.name, it.urIs.firstOrNull()?.toString() ?: "") }

            withContext(Dispatchers.Main) {
                setupRecyclerView(remotes.toMutableList())
            }
        }
    }

    private fun setupRecyclerView(remotes: MutableList<GitRemote>) {
        val adapter = RemotesAdapter(remotes) { remote ->
            val action =
                GitRemotesListFragmentDirections.actionGitRemotesListFragmentToGitEditRemoteFragment(
                    remote.name
                )
            findNavController().navigate(action)
        }
        binding.remotesRecyclerView.adapter = adapter
        binding.remotesRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Setup swipe to delete
        val itemTouchHelper =
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean = false

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val position = viewHolder.adapterPosition
                    val remoteToDelete = adapter.getRemoteAt(position)
                    deleteRemote(remoteToDelete, position)
                }
            })
        itemTouchHelper.attachToRecyclerView(binding.remotesRecyclerView)
    }

    private fun deleteRemote(remote: GitRemote, position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val projectDir = ProjectManagerImpl.getInstance().projectDir ?: return@launch
            val git = Git.open(projectDir)
            git.remoteRemove().setRemoteName(remote.name).call()
            withContext(Dispatchers.Main) {
                (binding.remotesRecyclerView.adapter as RemotesAdapter).removeAt(position)
                Snackbar.make(binding.root, "Deleted remote: ${remote.name}", Snackbar.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}