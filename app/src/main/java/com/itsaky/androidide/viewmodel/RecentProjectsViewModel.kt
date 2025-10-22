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
import java.io.File
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.sql.SQLException
import java.util.Date

class RecentProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val _projects = MutableLiveData<List<ProjectFile>>()
    val projects: LiveData<List<ProjectFile>> = _projects
    private val _deletionStatus = MutableSharedFlow<Boolean>()
    val deletionStatus = _deletionStatus.asSharedFlow()

    companion object {
        private val logger = LoggerFactory.getLogger(RecentProjectsViewModel::class.java)
    }

    // Get the database and DAO instance
    private val recentProjectDao: RecentProjectDao =
        RecentProjectRoomDatabase.getDatabase(application, viewModelScope).recentProjectDao()

    fun loadProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            val projectsFromDb = recentProjectDao.dumpAll() ?: emptyList()
            val context = getApplication<Application>().applicationContext
            val projectFiles =
                projectsFromDb.map { ProjectFile(it.location, it.createdAt, context) }
            _projects.postValue(projectFiles)
        }
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

    private fun deleteProject(name: String) = viewModelScope.launch {
        try {
            val success = withContext(Dispatchers.IO) {
                // Delete files from storage first
                val projectToDelete = recentProjectDao.getProjectByName(name)
                    ?: return@withContext false
                val isDeleted = File(projectToDelete.location).deleteRecursively()

                // Delete from DB if storage deletion was successful
                if (isDeleted) {
                    recentProjectDao.deleteByName(name)
                }
                isDeleted
            }

            if (success) {
                // Update LiveData
                val currentList = _projects.value ?: emptyList()
                _projects.value = currentList.filter { it.name != name }
                _deletionStatus.emit(true)
            } else {
                // Emit failure if files couldn't be deleted
                _deletionStatus.emit(false)
            }
        } catch (e: IOException) {
            logger.error("An I/O error occurred during project deletion", e)
            _deletionStatus.emit(false)
        } catch (e: SQLException) {
            logger.error("A database error occurred during project deletion", e)
            _deletionStatus.emit(false)
        }
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
        viewModelScope.launch {
            if (selectedNames.isEmpty()) {
                return@launch
            }
            try {
                // Delete from storage and database
                recentProjectDao.deleteProjectsAndFiles(selectedNames)

                // Update LiveData after successful deletion
                withContext(Dispatchers.Main) {
                    val currentProjects = _projects.value ?: emptyList()
                    _projects.value = currentProjects.filterNot { it.name in selectedNames }
                }
                _deletionStatus.emit(true)
            } catch (e: Exception) {
                logger.error("Failed to delete projects", e)
                _deletionStatus.emit(false)
            }
        }

}
