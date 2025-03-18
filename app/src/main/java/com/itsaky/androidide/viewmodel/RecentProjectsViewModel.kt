package com.itsaky.androidide.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectDao
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsvks.layouteditor.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

class RecentProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val _projects = MutableLiveData<List<ProjectFile>>()
    val projects: LiveData<List<ProjectFile>> = _projects


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


    fun deleteProject(name: String) = viewModelScope.launch(Dispatchers.IO) {
        recentProjectDao.deleteByName(name)
        // Update the LiveData by removing the deleted project
        _projects.value?.let { currentList ->
            val updatedList = currentList.filter { it.name != name }
            _projects.postValue(updatedList)
        }
        loadProjects()
    }

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
            //  update the LiveData to remove the deleted projects
            _projects.postValue(_projects.value?.filterNot { it.name in selectedNames })

        }
}
