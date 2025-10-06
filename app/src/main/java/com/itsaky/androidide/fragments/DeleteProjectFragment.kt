package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.adapters.DeleteProjectListAdapter
import com.itsaky.androidide.databinding.FragmentDeleteProjectBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.DELETE_PROJECT
import com.itsaky.androidide.idetooltips.TooltipTag.DELETE_PROJECT_BUTTON
import com.itsaky.androidide.idetooltips.TooltipTag.DELETE_PROJECT_CONFIRM
import com.itsaky.androidide.idetooltips.TooltipTag.DELETE_PROJECT_DIALOG
import com.itsaky.androidide.idetooltips.TooltipTag.DELETE_PROJECT_SELECT
import com.itsaky.androidide.idetooltips.TooltipTag.EXIT_TO_MAIN
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_NEW
import com.itsaky.androidide.ui.CustomDividerItemDecoration
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.RecentProjectsViewModel
import java.io.File

class DeleteProjectFragment : BaseFragment() {

    private var _binding: FragmentDeleteProjectBinding? = null
    private val binding get() = _binding!!

    private val recentProjectsViewModel: RecentProjectsViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private var adapter: DeleteProjectListAdapter? = null
    private var isDeleteButtonClickable = false

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
                adapter = DeleteProjectListAdapter(
                    projects,
                    { enableBtn ->
                        updateDeleteButtonState(enableBtn)
                    },
                    onCheckboxLongPress = {
                        showToolTip(DELETE_PROJECT_SELECT)
                        true
                    }

                )
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
                updateDeleteButtonState(adapter?.getSelectedProjects()?.isNotEmpty() ?: false)
            }
        }
    }

    private fun updateDeleteButtonState(hasSelection: Boolean) {
        isDeleteButtonClickable = hasSelection
        binding.delete.isEnabled = true
        binding.delete.alpha = if (hasSelection) 1.0f else 0.5f
    }

    private fun setupClickListeners() {
        binding.delete.setOnClickListener {
            // If no projects exist, navigate to the create project screen.
            val projects = recentProjectsViewModel.projects.value
            if (projects.isNullOrEmpty()) {
                mainViewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
            } else if (isDeleteButtonClickable) {
                showDeleteDialog()
            }
        }
        binding.delete.setOnLongClickListener {
            val projects = recentProjectsViewModel.projects.value

            val tooltipTag = if (projects.isNullOrEmpty()) {
                PROJECT_NEW
            } else {
                DELETE_PROJECT_BUTTON
            }
            showToolTip(tooltipTag)
            true
        }

        binding.exitButton.setOnClickListener { mainViewModel.setScreen(MainViewModel.SCREEN_MAIN) }
        binding.exitButton.setOnLongClickListener {
            showToolTip(EXIT_TO_MAIN)
            true
        }

        binding.recentProjectsTxt.setOnLongClickListener {
            showToolTip(DELETE_PROJECT)
            true
        }
    }

    private fun deleteProject(root: File) {
        (requireActivity() as MainActivity).deleteProject(root)
    }

    fun showToolTip(
        tag: String,
        anchorView: View? = null
    ) {
        anchorView ?: return
        TooltipManager.showTooltip(
            context = requireContext(),
            anchorView = anchorView,
            tag = tag,
        )
    }

    private fun showDeleteDialog() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_project)
            .setMessage(R.string.msg_delete_selected_project)
            .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    adapter?.getSelectedProjects().let { locations ->
                        locations?.forEach {
                            deleteProject(File(it.path))
                        }
                        val names = locations?.map { it.name }
                        if (names != null) {
                            recentProjectsViewModel.deleteSelectedProjects(names)
                        }
                        flashSuccess(R.string.deleted)
                    }
                } catch (e: Exception) {
                    flashError(R.string.delete_failed)
                }
            }
            .show()

        val contentView = dialog.findViewById<View>(android.R.id.content)
        contentView?.setOnLongClickListener {
            showToolTip(DELETE_PROJECT_DIALOG, contentView)
            true
        }

        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            ?.setOnLongClickListener { button ->
                showToolTip(DELETE_PROJECT_CONFIRM, button)
                true
            }
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

