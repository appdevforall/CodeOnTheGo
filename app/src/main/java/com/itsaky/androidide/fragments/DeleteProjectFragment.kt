package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.adapters.DeleteProjectListAdapter
import com.itsaky.androidide.databinding.FragmentDeleteProjectBinding
import com.itsaky.androidide.ui.CustomDividerItemDecoration
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.RecentProjectsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.io.File

class DeleteProjectFragment : BaseFragment() {

    private var _binding: FragmentDeleteProjectBinding? = null
    private val binding get() = _binding!!

    private val recentProjectsViewModel: RecentProjectsViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private var adapter: DeleteProjectListAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeleteProjectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeProjects()
        observeDeleteProjectsEvent()
        setupClickListeners()
    }

    private fun setupRecyclerView() = with(binding) {
        listProjects.layoutManager = LinearLayoutManager(requireContext())
        listProjects.addItemDecoration(
            CustomDividerItemDecoration(requireContext(), R.drawable.custom_list_divider)
        )
    }

    private fun observeProjects() {
        recentProjectsViewModel.projects.observe(viewLifecycleOwner) { projects ->
            if (adapter == null) {
                // Create adapter and pass a callback to update delete button state on selection change
                adapter = DeleteProjectListAdapter(projects) { enableBtn ->
                    binding.delete.isEnabled = enableBtn
                }
                binding.listProjects.adapter = adapter
            } else {
                adapter?.updateProjects(projects)
            }
            binding.recentProjectsTxt.isVisible = projects.isNotEmpty()
            binding.noProjectsView.isVisible = projects.isEmpty()

            // Change button text and state based on whether projects exist
            if (projects.isEmpty()) {
                binding.delete.text = getString(R.string.new_project)
                binding.delete.isEnabled = true
            } else {
                binding.delete.text = getString(R.string.delete_project)
                binding.delete.isEnabled = adapter?.getSelectedProjects()?.isNotEmpty() ?: false
            }
        }
    }

    private fun observeDeleteProjectsEvent() {
        recentProjectsViewModel.deleteProjectsEvent.observe(viewLifecycleOwner) { files ->
            if (!files.isNullOrEmpty()) {
                binding.loader.isVisible = true
                lifecycleScope.launch {
                    try {
                        files.map { path ->
                            async(Dispatchers.IO) {
                                deleteProject(File(path))
                            }
                        }.awaitAll()
                    } finally {
                        binding.loader.isVisible = false
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.delete.setOnClickListener {
            // If no projects exist, navigate to the create project screen.
            val projects = recentProjectsViewModel.projects.value
            if (projects.isNullOrEmpty()) {
                mainViewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
            } else {
                showDeleteDialog()
            }
        }
        binding.exitButton.setOnClickListener { mainViewModel.setScreen(MainViewModel.SCREEN_MAIN) }
    }

    private fun deleteProject(root: File) {
        (requireActivity() as MainActivity).deleteProject(root)
    }

    private fun showDeleteDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(com.itsvks.layouteditor.R.string.delete_project)
            .setMessage(com.itsvks.layouteditor.R.string.msg_delete_project)
            .setNegativeButton(com.itsvks.layouteditor.R.string.no) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(com.itsvks.layouteditor.R.string.yes) { _, _ ->
                adapter?.let {
                    // Map selected ProjectFile items to their names for deletion.
                    recentProjectsViewModel.deleteSelectedProjects(
                        it.getSelectedProjects().map { project -> project })
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        recentProjectsViewModel.loadProjects()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
