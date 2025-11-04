package com.itsaky.androidide.fragments


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.adapters.RecentProjectsAdapter
import com.itsaky.androidide.databinding.FragmentSavedProjectsBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.EXIT_TO_MAIN
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_NEW
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_OPEN_FOLDER
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_RECENT_TOP
import com.itsaky.androidide.ui.CustomDividerItemDecoration
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.RecentProjectsViewModel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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

	private fun pickProjectDirectory(
		isLongClick: Boolean,
	) {
		if (isLongClick) {
			showToolTip(PROJECT_OPEN_FOLDER)
			return
		}

		pickDirectory { selectedDir ->
			if (!isValidProjectOrContainerDirectory(selectedDir)) {
				flashError(
					msg = requireContext().getString(
						R.string.project_directory_invalid,
						selectedDir.name
					)
				)
				return@pickDirectory
			}

			onProjectDirectoryPicked(selectedDir)
		}
	}

    private fun onProjectDirectoryPicked(directory: File) {
			// Is the current folder a valid android project?
			// Yes: Then open it.
			if (isValidProjectDirectory(directory)) {
				viewModel.insertProjectFromFolder(
					name = directory.name,
					location = directory.absolutePath
				)

				openProject(root = directory)
				return
			}

			// No, the current folder is a container of Android Projects: Then open the first valid one.
			val subFolders = directory.listFiles()

			if (subFolders == null) {
				flashError(getString(R.string.msg_cannot_access_folder, directory.name))
				return
			}
			if (subFolders.isEmpty()) {
					flashError(getString(R.string.msg_no_subfolders, directory.name))
					return
			}

			val validSubDirs = subFolders.filter { it.isDirectory }

			val validProjects = validSubDirs.filter { isValidProjectDirectory(it) }
			val invalidProjects = validSubDirs - validProjects.toSet()

			when {
				validProjects.isEmpty() -> {
					flashError(getString(R.string.msg_no_valid_projects, directory.name))
					return
				}
				invalidProjects.isNotEmpty() -> {
					flashError(getString(R.string.msg_skipped_invalid_projects))
				}
			}


			val jobs = validProjects.map { sub ->
				viewModel.insertProjectFromFolder(sub.name, sub.absolutePath)
			}

			viewLifecycleScope.launch {
				jobs.joinAll()
				openProject(root = validProjects.first())
			}
    }

    private fun setupObservers() {
        viewModel.projects.observe(viewLifecycleOwner) { projects ->
            if (!::adapter.isInitialized) {
				adapter = RecentProjectsAdapter(
					projects,
					onProjectClick = ::openProject,
					onOpenFileFromFolderClick = ::pickProjectDirectory,
					onRemoveProjectClick = viewModel::deleteProject,
					onFileRenamed = viewModel::updateProject,
				)
				binding.listProjects.adapter = adapter
			} else {
				adapter.updateProjects(projects)
			}

            val isEmpty = projects.isEmpty()
            binding.recentProjectsTxt.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.noProjectsView.visibility = if (isEmpty) View.VISIBLE else View.GONE

            binding.tvCreateNewProject.setText(R.string.msg_create_new_from_recent)
            binding.btnOpenFromFolder.setOnClickListener {
                pickProjectDirectory(isLongClick = false)
            }
        }
    }

	fun isValidProjectDirectory(selectedDir: File): Boolean {
        val appFolder = File(selectedDir, "app")
        val buildGradleFile = File(appFolder, "build.gradle")
        val buildGradleKtsFile = File(appFolder, "build.gradle.kts")
        return appFolder.exists() && appFolder.isDirectory &&
                (buildGradleFile.exists() || buildGradleKtsFile.exists())
    }

    /**
     * Determines if the selected directory is either:
     *  1. A valid Android project itself, OR
     *  2. A container that includes one or more valid Android projects.
     */
    fun isValidProjectOrContainerDirectory(selectedDir: File): Boolean {
        if (isValidProjectDirectory(selectedDir)) {
            return true
        }

        // Check if it contains valid Android projects as subdirectories
        val subDirs = selectedDir.listFiles()?.filter { it.isDirectory } ?: return false
        return subDirs.any { sub -> isValidProjectDirectory(sub) }
    }

    private fun setupClickListeners() {
        binding.newProjectButton.setOnClickListener {
            mainViewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
        }
        binding.exitButton.setOnClickListener {
            mainViewModel.setScreen(MainViewModel.SCREEN_MAIN)
        }
        binding.exitButton.setOnLongClickListener {
            showToolTip(EXIT_TO_MAIN)
            true
        }
        binding.newProjectButton.setOnLongClickListener {
            showToolTip(PROJECT_NEW)
            true
        }

        binding.recentProjectsTxt.setOnLongClickListener {
            showToolTip(PROJECT_RECENT_TOP)
            true
        }
    }

    private fun openProject(root: File) {
        (requireActivity() as MainActivity).openProject(root)
    }

	override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadProjects()
    }

    private fun showToolTip(tag: String) {
        TooltipManager.showIdeCategoryTooltip(requireContext(), binding.root, tag)
    }

}

