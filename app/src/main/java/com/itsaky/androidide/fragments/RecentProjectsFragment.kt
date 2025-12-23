package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
import com.itsaky.androidide.utils.Environment.PROJECTS_DIR
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.RecentProjectsViewModel
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.viewmodel.SortCriteria
import com.itsaky.androidide.ui.ProjectInfoBottomSheet
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import com.itsaky.androidide.utils.flashSuccess
import java.io.File

class RecentProjectsFragment : BaseFragment() {
	@Suppress("ktlint:standard:backing-property-naming")
    private var _binding: FragmentSavedProjectsBinding? = null
    private val binding get() = _binding!!

	private val viewModel: RecentProjectsViewModel by activityViewModels()
	private val mainViewModel: MainViewModel by activityViewModels()
	private lateinit var adapter: RecentProjectsAdapter
	private var selectedCriteria: SortCriteria? = null
	private var selectedAsc = true
	private val searchQuery = MutableStateFlow("")

	data class SortToggleStyle(
		val iconRes: Int,
		val colorRes: Int
	)

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		_binding = FragmentSavedProjectsBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		setupRecyclerView()
		setupSearchBar()
		setupObservers()
		setupClickListeners()
        bootstrapFromFixedFolderIfNeeded()
        observeDeletionStatus()
	}

	private fun setupRecyclerView() {
		binding.listProjects.layoutManager = LinearLayoutManager(requireContext())
		binding.listProjects.addItemDecoration(
			CustomDividerItemDecoration(requireContext(), R.drawable.custom_list_divider),
		)
	}

	private fun openFiltersSheet() {
		val dialog = BottomSheetDialog(requireContext())
		val sheet = layoutInflater.inflate(R.layout.layout_project_filters_sheet, null)

		dialog.setContentView(sheet)
		setupFilters(sheet)

		viewLifecycleScope.launch {
			viewModel.filterEvents.collect { dialog.dismiss() }
		}

		dialog.show()
	}

	@OptIn(kotlinx.coroutines.FlowPreview::class)
	private fun setupSearchBar() {
		viewLifecycleScope.launch {
			searchQuery
				.debounce(300)
				.collect { query -> viewModel.onSearchQuery(query) }
		}

		binding.layoutFilters.searchProjectEditText.addTextChangedListener { text ->
			searchQuery.value = text?.toString().orEmpty()
		}
	}

	private fun setupFilters(sheet: View) {
		val sortDropdown = sheet.requireViewById<MaterialAutoCompleteTextView>(R.id.sort_dropdown)
		val sortToggleBtn = sheet.requireViewById<MaterialButton>(R.id.sort_toggle_btn)
		val applyBtn = sheet.requireViewById<MaterialButton>(R.id.apply_filters_btn)
		val clearBtn = sheet.requireViewById<MaterialButton>(R.id.clear_filters_btn)

		selectedCriteria = viewModel.currentSortCriteria
		selectedAsc = viewModel.currentSortAscending

		setupSortUI(sortDropdown, sortToggleBtn)
		clearBtn.isVisible = viewModel.hasActiveFilters

		sortDropdown.setOnItemClickListener { _, _, position, _ ->
			selectedCriteria = when (position) {
				0 -> SortCriteria.NAME
				1 -> SortCriteria.DATE_CREATED
				2 -> SortCriteria.DATE_MODIFIED
				else -> SortCriteria.NAME
			}

			clearBtn.isVisible = true
		}

		sortToggleBtn.setOnClickListener {
			toggleSortDirection(sortToggleBtn)
			clearBtn.isVisible = true
		}

		applyBtn.setOnClickListener {
			viewLifecycleScope.launch {
				selectedCriteria?.let { viewModel.onSortSelected(it) }
				viewModel.onSortDirectionChanged(selectedAsc)
			}
			viewModel.notifyFiltersSaved()
		}

		clearBtn.setOnClickListener {
			selectedCriteria = null
			selectedAsc = true

			sortDropdown.text = null
			setupSortToggle(sortToggleBtn, true)
			binding.layoutFilters.searchProjectEditText.text?.clear()

			clearBtn.isVisible = false
			viewLifecycleScope.launch {
				viewModel.clearFilters()
			}

			viewModel.notifyFiltersSaved()
		}
	}

	/**
	 * Initializes UI according to current VM state.
	 */
	private fun setupSortUI(
		sortDropdown: MaterialAutoCompleteTextView,
		sortToggleBtn: MaterialButton
	) {
		val labelRes = when (selectedCriteria) {
			SortCriteria.NAME -> R.string.sort_by_name
			SortCriteria.DATE_CREATED -> R.string.sort_by_created
			SortCriteria.DATE_MODIFIED -> R.string.sort_by_modified
			null -> null
		}

		if (labelRes != null) {
			sortDropdown.setText(getString(labelRes), false)
		} else {
			sortDropdown.text = null
		}

		setupSortToggle(sortToggleBtn, selectedAsc)
	}

	/**
	 * Updates the arrow icon, the background color and the text based on ascending state.
	 */
	private fun setupSortToggle(button: MaterialButton, asc: Boolean) {
		val style = if (asc) {
			SortToggleStyle(
				R.drawable.ic_arrow_up, R.color._blue_wave_light_colorPrimaryDark
			)
		} else {
			SortToggleStyle(
				R.drawable.ic_arrow_down, R.color._blue_wave_dark_colorOnSecondary
			)
		}
		button.apply {
			setIconResource(style.iconRes)
			backgroundTintList = ContextCompat.getColorStateList(context, style.colorRes)
		}
	}

	/**
	 * Toggles the ascending/descending state and updates the UI.
	 */
	private fun toggleSortDirection(button: MaterialButton) {
		selectedAsc = !selectedAsc
		setupSortToggle(button, selectedAsc)
	}


    private fun File.isProjectCandidateDir(): Boolean = isDirectory && canRead() && !name.startsWith(".") && !isHidden

    private fun bootstrapFromFixedFolderIfNeeded() {
        if (viewModel.didBootstrap) return
        viewModel.didBootstrap = true

        viewLifecycleScope.launch(Dispatchers.IO) {
            try {
                val validProjects = findValidProjects(PROJECTS_DIR)
                if (validProjects.isEmpty()) return@launch

                loadProjectsIntoViewModel(validProjects)

                if (GeneralPreferences.autoOpenProjects) {
                    val lastOpenedPath = GeneralPreferences.lastOpenedProject

                    val projectToOpen = validProjects.find {
                        it.absolutePath == lastOpenedPath
                    }

                    if (projectToOpen != null) {
                        withContext(Dispatchers.Main) { openProject(projectToOpen) }
                        return@launch
                    }

                    val lastCreated = validProjects.maxByOrNull { it.lastModified() }

                    if (lastCreated != null) {
                        withContext(Dispatchers.Main) { openProject(lastCreated) }
                    }
                }
            } catch (e: Throwable) {
                Sentry.captureException(e)
            }
        }
    }

    private fun findValidProjects(projectsRoot: File): List<File> {
        if (!projectsRoot.isProjectCandidateDir()) return emptyList()

        val subdirs = projectsRoot.listFiles()
            ?.filter { it.isProjectCandidateDir() }
            .orEmpty()
        if (subdirs.isEmpty()) return emptyList()

        return subdirs.filter { dir -> isValidProjectDirectory(dir) }
    }

    private suspend fun loadProjectsIntoViewModel(projects: List<File>) {
        val jobs = projects.map { dir ->
            viewModel.insertProjectFromFolder(dir.name, dir.absolutePath)
        }
        jobs.joinAll()

        val loadJob = viewModel.loadProjects()
        loadJob.join()
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
					msg =
						requireContext().getString(
							R.string.project_directory_invalid,
							selectedDir.name,
						),
				)
				return@pickDirectory
			}

			onProjectDirectoryPicked(selectedDir)
		}
	}

    private fun onProjectDirectoryPicked(directory: File) {
			if (!directory.isProjectCandidateDir()) {
				flashError(getString(R.string.msg_cannot_access_folder, directory.name))
				return
			}

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

			val validSubDirs = subFolders.filter { it.isProjectCandidateDir() }

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
          onRemoveProjectClick = viewModel::deleteProject,
					onFileRenamed = viewModel::updateProject,
					onInfoClick = { project -> openProjectInfo(project) }
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

            binding.openFromFolderBtn.apply {
                isVisible = !isEmpty
                setOnClickListener { pickProjectDirectory(isLongClick = false) }
                setOnLongClickListener {
                    TooltipManager.showIdeCategoryTooltip(
                        context = context,
                        anchorView = this,
                        tag = PROJECT_OPEN_FOLDER
                    )
                    true
                }
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
        if (!selectedDir.isProjectCandidateDir()) {
            return false
        }

        if (isValidProjectDirectory(selectedDir)) {
            return true
        }

        // Check if it contains valid Android projects as subdirectories
        val subDirs = selectedDir.listFiles()?.filter { it.isProjectCandidateDir() } ?: return false
        return subDirs.any { sub -> isValidProjectDirectory(sub) }
    }

		private fun openProjectInfo(project: ProjectFile) {
		    viewLifecycleScope.launch {
		        val recentProject = viewModel.getProjectByName(project.name)

		        val sheet = ProjectInfoBottomSheet.newInstance(project, recentProject)
		        sheet.show(parentFragmentManager, "project_info_sheet")
		    }
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
        binding.layoutFilters.openFiltersBtn.setOnClickListener {
            openFiltersSheet()
        }
    }

    private fun openProject(root: File) {
        (requireActivity() as MainActivity).openProject(root)
    }

	override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

	private fun showToolTip(tag: String) {
		TooltipManager.showIdeCategoryTooltip(requireContext(), binding.root, tag)
	}

    override fun onResume() {
        super.onResume()
        viewModel.loadProjects()
    }

    private fun observeDeletionStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deletionStatus.collect { status ->
                if (status) {
                    flashSuccess(R.string.deleted)
                } else {
                    flashError(R.string.delete_failed)
                }
            }
        }
    }

}
