package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.adapters.RecentProjectsAdapter
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectDao
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date

// TODO: Add last modified filter.
enum class SortCriteria {
    NAME,
    DATE_CREATED,
}

class RecentProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val _projects = MutableLiveData<List<ProjectFile>>()
    private var allProjects: List<ProjectFile> = emptyList()
    val projects: LiveData<List<ProjectFile>> = _projects
    var didBootstrap = false
    private var currentQuery: String = ""
    private var currentSort: SortCriteria? = null
    private var isAscending: Boolean = true

    val currentSortCriteria: SortCriteria? get() = currentSort
    val currentSortAscending: Boolean get() = isAscending
    val hasActiveFilters: Boolean
        get() = currentSort != null || !isAscending || currentQuery.isNotEmpty()


    // Get the database and DAO instance
    private val recentProjectDao: RecentProjectDao =
        RecentProjectRoomDatabase.getDatabase(application, viewModelScope).recentProjectDao()

    fun loadProjects(): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            val projectsFromDb = recentProjectDao.dumpAll() ?: emptyList()
            val context = getApplication<Application>().applicationContext

            allProjects = projectsFromDb.map { ProjectFile(it.location, it.createdAt, context) }
            applyFilters()
        }
    }

    // TODO: Add last modified filter.
    private fun applyFilters() {
        var result = allProjects

        if (currentQuery.isNotEmpty()) {
            result = result.filter { it.name.contains(currentQuery, ignoreCase = true) }
        }

        currentSort.let { criteria ->
            result = when (criteria) {
                SortCriteria.NAME -> result.sortedBy { it.name.lowercase() }
                SortCriteria.DATE_CREATED -> result.sortedBy { it.date }
                else -> result
            }
            if (!isAscending) { result = result.reversed() }
        }
        _projects.postValue(result)
    }

    fun onSearchQuery(query: String) {
        currentQuery = query.trim()
        applyFilters()
    }

    fun onSortSelected(criteria: SortCriteria?) {
        currentSort = criteria
        applyFilters()
    }

    fun onSortDirectionChanged(ascending: Boolean) {
        isAscending = ascending
        applyFilters()
    }

    fun clearFilters() {
        currentSort = null
        isAscending = true
        currentQuery = ""
        applyFilters()
    }

    fun insertProject(project: RecentProject) = viewModelScope.launch(Dispatchers.IO) {
        recentProjectDao.insert(project)
    }

    fun insertProjectFromFolder(name: String, location: String) =
        viewModelScope.launch(Dispatchers.IO) {
            // Check if the project already exists
            val existingProject = recentProjectDao.getProjectByName(name)
            if (existingProject == null) {
                recentProjectDao.insert(
                    RecentProject(
                        location = location,
                        name = name,
                        createdAt = Date().toString()
                    )
                )
            }
        }


	fun deleteProject(project: ProjectFile) = deleteProject(project.name)

    fun deleteProject(name: String) = viewModelScope.launch(Dispatchers.IO) {
        recentProjectDao.deleteByName(name)
        // Update the LiveData by removing the deleted project
        _projects.value?.let { currentList ->
            val updatedList = currentList.filter { it.name != name }
            _projects.postValue(updatedList)
        }
        loadProjects()
    }

	fun updateProject(renamedFile: RecentProjectsAdapter.RenamedFile) =
		updateProject(renamedFile.oldName, renamedFile.newName, renamedFile.newPath)

    fun updateProject(oldName: String, newName: String, location: String) =
        viewModelScope.launch(Dispatchers.IO) {
            recentProjectDao.updateNameAndLocation(
                oldName = oldName,
                newName = newName,
                newLocation = location
            )
            loadProjects()
        }

    fun deleteSelectedProjects(selectedNames: List<String>) =
        viewModelScope.launch(Dispatchers.IO) {
            // Delete the selected projects from the database
            recentProjectDao.deleteByNames(selectedNames)
            loadProjects()
        }
}
