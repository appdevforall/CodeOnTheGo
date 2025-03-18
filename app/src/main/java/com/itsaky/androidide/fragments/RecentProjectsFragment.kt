package com.itsaky.androidide.fragments

import android.os.Bundle
import android.text.SpannableStringBuilder

import android.text.style.ClickableSpan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R


import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.adapters.RecentProjectsAdapter
import com.itsaky.androidide.databinding.FragmentSavedProjectsBinding
import com.itsaky.androidide.ui.CustomDividerItemDecoration
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.RecentProjectsViewModel
import java.io.File

class RecentProjectsFragment : BaseFragment() {

    private var _binding: FragmentSavedProjectsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecentProjectsViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: RecentProjectsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSavedProjectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupObservers()
        setupClickListeners()
    }

    private fun setupRecyclerView() {
        binding.listProjects.layoutManager = LinearLayoutManager(requireContext())
        binding.listProjects.addItemDecoration(
            CustomDividerItemDecoration(requireContext(), R.drawable.custom_list_divider)
        )
    }

    private fun setupObservers() {
        // Lambda to handle directory picking: insert the project and open it.
        val handleDirectoryPick: (File) -> Unit = { file ->
            viewModel.insertProjectFromFolder(
                name = file.name, location = file.absolutePath
            )
            openProject(file)
        }

        viewModel.projects.observe(viewLifecycleOwner) { projects ->
            if (::adapter.isInitialized) {
                adapter.updateProjects(projects)
            } else {
                adapter = RecentProjectsAdapter(
                    projects,
                    onProjectClick = { openProject(it) },
                    onOpenFileFromFolderClick = {
                        pickDirectory { handleDirectoryPick(it) }
                    },
                    onRemoveProjectClick = { project ->
                        viewModel.deleteProject(project.name)
                    },
                    onFileRenamed = {
                        viewModel.updateProject(it.oldName, it.newName, it.newPath)
                    },
                )
                binding.listProjects.adapter = adapter
            }

            val isEmpty = projects.isEmpty()
            binding.recentProjectsTxt.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.noProjectsView.visibility = if (isEmpty) View.VISIBLE else View.GONE

            // Build the clickable spannable text.
            val sb = SpannableStringBuilder()
            val filesSpan: ClickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    pickDirectory { handleDirectoryPick(it) }
                }
            }
            appendClickableSpan(sb, R.string.msg_create_new_from_recent, filesSpan)
            binding.tvCreateNewProject.text = sb

            // Also set a click listener on the TextView itself.
            binding.tvCreateNewProject.setOnClickListener {
                pickDirectory { handleDirectoryPick(it) }
            }
        }
    }


    private fun setupClickListeners() {
        binding.newProjectButton.setOnClickListener {
            mainViewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
        }
        binding.exitButton.setOnClickListener {
            mainViewModel.setScreen(MainViewModel.SCREEN_MAIN)
        }
    }

    private fun openProject(root: File) {
        (requireActivity() as MainActivity).openProject(root)
    }

    private fun deleteProject(root: File) {
        (requireActivity() as MainActivity).deleteProject(root)
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadProjects()
    }
}
