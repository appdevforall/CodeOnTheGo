package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.adapters.RecentProjectsAdapter
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectDao
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.utils.getCreatedTime
import com.itsaky.androidide.utils.getLastModifiedTime
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortCriteria {
    NAME,
    DATE_CREATED,
    DATE_MODIFIED
}

class RecentProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val _projects = MutableLiveData<List<ProjectFile>>()
    private var allProjects: List<ProjectFile> = emptyList()
    val projects: LiveData<List<ProjectFile>> = _projects
    private val _filterEvents = MutableSharedFlow<Unit>()
    val filterEvents = _filterEvents
    var didBootstrap = false
    private var currentQuery: String = ""
    private var currentSort: SortCriteria? = null
    private var isAscending: Boolean = true

    val currentSortCriteria: SortCriteria? get() = currentSort
    val currentSortAscending: Boolean get() = isAscending
    val hasActiveFilters: Boolean
        get() = currentSort != null || !isAscending || currentQuery.isNotEmpty()


    // Get the database and DAO instance
    private val recentProjectDatabase: RecentProjectRoomDatabase =
        RecentProjectRoomDatabase.getDatabase(application, viewModelScope)
    private val recentProjectDao: RecentProjectDao = recentProjectDatabase.recentProjectDao()

    fun loadProjects(): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            val projectsFromDb = recentProjectDao.dumpAll() ?: emptyList()
            val context = getApplication<Application>().applicationContext

            allProjects = projectsFromDb.map { ProjectFile(it.location, it.createdAt, it.lastModified, context) }
            applyFilters()
        }
    }

    fun notifyFiltersSaved() {
        viewModelScope.launch {
            _filterEvents.emit(Unit)
        }
    }

    private suspend fun applyFilters() {
        withContext(Dispatchers.Default) {
            var result = allProjects

            if (currentQuery.isNotEmpty()) {
                result = result.filter { it.name.contains(currentQuery, ignoreCase = true) }
            }

            currentSort.let { criteria ->
                result = when (criteria) {
                    SortCriteria.NAME -> result.sortedBy { it.name.lowercase() }
                    SortCriteria.DATE_CREATED -> result.sortedBy { it.createdAt }
                    SortCriteria.DATE_MODIFIED -> result.sortedBy { it.lastModified }
                    else -> result
                }
                if (!isAscending) {
                    result = result.reversed()
                }
            }
            _projects.postValue(result)
        }
    }

    suspend fun onSearchQuery(query: String) {
        currentQuery = query.trim()
        applyFilters()
    }

    suspend fun onSortSelected(criteria: SortCriteria?) {
        currentSort = criteria
        applyFilters()
    }

    suspend fun onSortDirectionChanged(ascending: Boolean) {
        isAscending = ascending
        applyFilters()
    }

    suspend fun clearFilters() {
        currentSort = null
        isAscending = true
        currentQuery = ""
        applyFilters()
    }

    fun insertProject(project: RecentProject) = viewModelScope.launch(Dispatchers.IO) {
        recentProjectDao.insert(project)
    }

    suspend fun getProjectByName(name: String): RecentProject? {
        return withContext(Dispatchers.IO) {
            recentProjectDao.getProjectByName(name)
        }
    }

    fun insertProjectFromFolder(name: String, location: String) =
        viewModelScope.launch(Dispatchers.IO) {
            // Check if the project already exists
            val existingProject = getProjectByName(name)
            if (existingProject == null) {
                val createdAt = getCreatedTime(location)
                val modifiedAt = getLastModifiedTime(location)
                val unknown = application.getString(R.string.unknown)
                recentProjectDao.insert(
                    RecentProject(
                        location = location,
                        name = name,
                        createdAt = createdAt.toString(),
                        lastModified = modifiedAt.toString(),
                        templateName = unknown,
                        language = unknown
                    )
                )
            }
        }


	fun deleteProject(project: ProjectFile) = deleteProject(project.name)

    fun deleteProject(name: String) = viewModelScope.launch(Dispatchers.IO) {
        recentProjectDao.deleteByName(name)
        vacuumDatabase()
        loadProjects()
    }

	fun updateProject(renamedFile: RecentProjectsAdapter.RenamedFile) =
		updateProject(renamedFile.oldName, renamedFile.newName, renamedFile.newPath)

    fun updateProject(oldName: String, newName: String, location: String) =
        viewModelScope.launch(Dispatchers.IO) {
            val modifiedAt = System.currentTimeMillis().toString()
            recentProjectDao.updateNameAndLocation(
                oldName = oldName,
                newName = newName,
                newLocation = location
            )
            recentProjectDao.updateLastModified(
                projectName = newName,
                lastModified = modifiedAt
            )
            loadProjects()
        }

	fun updateProjectModifiedDate(name: String) =
		viewModelScope.launch(Dispatchers.IO) {
			val modifiedAt = System.currentTimeMillis()
			recentProjectDao.updateLastModified(
			    projectName = name,
			    lastModified = modifiedAt.toString()
			)
			loadProjects()
		}

  fun deleteSelectedProjects(selectedNames: List<String>) =
    viewModelScope.launch(Dispatchers.IO) {
      if (selectedNames.isEmpty()) return@launch
      // Delete the selected projects from the database
      recentProjectDao.deleteByNames(selectedNames)
      vacuumDatabase()
      loadProjects()
    }

  private suspend fun vacuumDatabase() {
    withContext(Dispatchers.IO) {
      runCatching {
        recentProjectDatabase.openHelper.writableDatabase.execSQL("VACUUM")
      }
    }
  }
}
