package com.itsaky.androidide.viewmodel

import android.app.Application
import android.database.SQLException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.itsaky.androidide.adapters.RecentProjectsAdapter
import com.itsaky.androidide.models.ProjectFile
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectDao
import com.itsaky.androidide.roomData.recentproject.RecentProjectMaintenance
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.utils.canonicalProjectLocation
import com.itsaky.androidide.utils.getCreatedTime
import com.itsaky.androidide.utils.getLastModifiedTime
import com.itsaky.androidide.utils.readProjectLanguage
import com.itsaky.androidide.utils.reconcileRecentProjectLocations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

enum class SortCriteria {
	NAME,
	DATE_CREATED,
	DATE_MODIFIED,
}

data class FilterState(
	val query: String = "",
	val sort: SortCriteria? = null,
	val ascending: Boolean = true,
) {
	val hasAny: Boolean get() = sort != null || query.isNotEmpty()
}

class RecentProjectsViewModel(
	application: Application,
) : AndroidViewModel(application) {
	companion object {
		private val logger = LoggerFactory.getLogger(RecentProjectsViewModel::class.java)
		private val projectLocationReconciliationMutex = Mutex()

		@Volatile private var projectLocationsReconciled = false
		private const val LOCATION_RECONCILIATION_KEY = "recent_project_locations_reconciled"
	}

	private val _projects = MutableLiveData<List<ProjectFile>>()
	private var allProjects: List<ProjectFile> = emptyList()
	val projects: LiveData<List<ProjectFile>> = _projects
	private val _filterEvents = MutableSharedFlow<Unit>()
	val filterEvents = _filterEvents
	var didBootstrap = false
	private var currentQuery: String = ""
	private var currentSort: SortCriteria? = null
	private var isAscending: Boolean = true

	private val _filterState = MutableStateFlow(FilterState())
	val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

	val currentSortCriteria: SortCriteria? get() = currentSort
	val currentSortAscending: Boolean get() = isAscending
	val hasActiveFilters: Boolean
		get() = _filterState.value.hasAny

	private val _deletionStatus = MutableSharedFlow<Boolean>(replay = 1)
	val deletionStatus = _deletionStatus.asSharedFlow()

	private val _renameStatus = MutableSharedFlow<Boolean>()
	val renameStatus = _renameStatus.asSharedFlow()

	// Get the database and DAO instance
	private val recentProjectDatabase: RecentProjectRoomDatabase =
		RecentProjectRoomDatabase.getDatabase(application, viewModelScope)
	private val recentProjectDao: RecentProjectDao = recentProjectDatabase.recentProjectDao()

	fun loadProjects(): Job =
		viewModelScope.launch(Dispatchers.IO) {
			val projectsFromDb =
				try {
					loadProjectsFromDatabase()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					logger.error("Failed to load recent projects", e)
					try {
						recentProjectDao.dumpAll() ?: emptyList()
					} catch (fallbackError: CancellationException) {
						throw fallbackError
					} catch (fallbackError: Exception) {
						logger.error("Failed to load recent projects after reconciliation error", fallbackError)
						emptyList()
					}
				}
			allProjects = projectsFromDb.map { ProjectFile(it.location, it.createdAt, it.lastModified) }
			applyFilters()
		}

	private suspend fun loadProjectsFromDatabase(): List<RecentProject> {
		if (projectLocationsReconciled) {
			return recentProjectDao.dumpAll() ?: emptyList()
		}

		// SQLite cannot resolve filesystem aliases; reconcile legacy rows once before reading Recents.
		return projectLocationReconciliationMutex.withLock {
			if (projectLocationsReconciled) {
				return@withLock recentProjectDao.dumpAll() ?: emptyList()
			}

			val projects =
				recentProjectDatabase.withTransaction {
					val maintenanceDao = recentProjectDatabase.maintenanceDao()
					val complete = maintenanceDao.isCompleted(LOCATION_RECONCILIATION_KEY) == true
					if (!complete) {
						reconcileCanonicalProjectLocations()
						maintenanceDao.setCompleted(
							RecentProjectMaintenance(LOCATION_RECONCILIATION_KEY, completed = true),
						)
					}
					recentProjectDao.dumpAll() ?: emptyList()
				}
			projectLocationsReconciled = true
			projects
		}
	}

	private suspend fun reconcileCanonicalProjectLocations() {
		val projects = recentProjectDao.dumpAll() ?: emptyList()
		val reconciled = reconcileRecentProjectLocations(projects)
		val retainedIds = reconciled.mapTo(mutableSetOf()) { it.id }
		val duplicateIds = projects.filterNot { it.id in retainedIds }.map { it.id }
		if (duplicateIds.isNotEmpty()) {
			recentProjectDao.deleteByIds(duplicateIds)
		}
		val originalById = projects.associateBy { it.id }
		reconciled.forEach { project ->
			if (originalById[project.id] != project) {
				recentProjectDao.update(project)
			}
		}
	}

	fun notifyFiltersSaved() {
		viewModelScope.launch {
			_filterEvents.emit(Unit)
		}
	}

	private suspend fun applyFilters() {
		_filterState.value = FilterState(currentQuery, currentSort, isAscending)
		withContext(Dispatchers.Default) {
			var result = allProjects

			if (currentQuery.isNotEmpty()) {
				result = result.filter { it.name.contains(currentQuery, ignoreCase = true) }
			}

			val criteria = currentSort
			if (criteria != null) {
				result =
					when (criteria) {
						SortCriteria.NAME -> result.sortedBy { it.name.lowercase() }
						SortCriteria.DATE_CREATED -> result.sortedBy { it.createdAt }
						SortCriteria.DATE_MODIFIED -> result.sortedBy { it.lastModified }
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

	suspend fun clearSort() {
		currentSort = null
		isAscending = true
		applyFilters()
	}

	suspend fun getProjectByLocation(location: String): RecentProject? =
		withContext(Dispatchers.IO) {
			recentProjectDao.getProjectByLocation(File(location).canonicalProjectLocation())
		}

	fun renameTargetExists(
		project: ProjectFile,
		newName: String,
	): Boolean {
		if (newName.equals(project.name, ignoreCase = true)) return false
		val projectDirectory = File(project.path).parentFile ?: return false
		val targetPath = File(projectDirectory, newName).absolutePath
		return allProjects.any { it.path == targetPath }
	}

	fun insertProjectFromFolder(
		name: String,
		location: String,
	) = viewModelScope.launch(Dispatchers.IO) {
		val projectLocation = File(location).canonicalProjectLocation()
		// Check by location so different projects may share a name without blocking import.
		val existingProject = recentProjectDao.getProjectByLocation(projectLocation)
		if (existingProject == null) {
			val createdAt = getCreatedTime(projectLocation)
			val modifiedAt = getLastModifiedTime(projectLocation)
			val unknown = Language.Unknown.lang
			val detectedLanguage = readProjectLanguage(File(projectLocation))
			val languageToStore = if (detectedLanguage != unknown) detectedLanguage else unknown
			recentProjectDao.insert(
				RecentProject(
					location = projectLocation,
					name = name,
					createdAt = createdAt.toString(),
					lastModified = modifiedAt.toString(),
					templateName = unknown,
					language = languageToStore,
				),
			)
		}
	}

	fun deleteProject(project: ProjectFile) = deleteProjectByLocation(project.path)

	suspend fun removeProjectFromRecents(location: String) =
		withContext(Dispatchers.IO) {
			try {
				recentProjectDao.deleteByLocation(File(location).canonicalProjectLocation())
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				logger.error("Failed to remove missing project from Recents", e)
			}
		}

	fun deleteProjectByLocation(location: String) =
		viewModelScope.launch {
			try {
				val success =
					withContext(Dispatchers.IO) {
						val projectLocation = File(location).canonicalProjectLocation()
						val projectToDelete =
							recentProjectDao.getProjectByLocation(projectLocation)
								?: return@withContext null
						val isDeleted = File(projectToDelete.location).deleteRecursively()

						if (isDeleted) {
							recentProjectDao.deleteByLocation(projectToDelete.location)
						}
						projectToDelete.takeIf { isDeleted }
					}

				if (success != null) {
					// Update LiveData
					val currentList = _projects.value ?: emptyList()
					allProjects = allProjects.filter { it.path != success.location }
					_projects.value = currentList.filter { it.path != success.location }
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
			} catch (e: SecurityException) {
				logger.error("Security error during project deletion", e)
				_deletionStatus.emit(false)
			}
			vacuumDatabase()
		}

	fun updateProject(renamedFile: RecentProjectsAdapter.RenamedFile) =
		updateProject(
			renamedFile.oldName,
			renamedFile.newName,
			renamedFile.oldPath,
			renamedFile.newPath,
		)

	fun updateProject(
		oldName: String,
		newName: String,
		oldLocation: String,
		newLocation: String,
	) = viewModelScope.launch(Dispatchers.IO) {
		try {
			val modifiedAt = System.currentTimeMillis().toString()
			val oldProjectLocation = File(oldLocation).canonicalProjectLocation()
			val newProjectLocation = File(newLocation).canonicalProjectLocation()
			recentProjectDao.updateNameAndLocation(
				oldLocation = oldProjectLocation,
				newName = newName,
				newLocation = newProjectLocation,
			)
			recentProjectDao.updateLastModified(
				location = newProjectLocation,
				lastModified = modifiedAt,
			)
			loadProjects()
			_renameStatus.emit(true)
		} catch (e: SQLException) {
			logger.error("Failed to update project after rename ($oldName -> $newName)", e)
			val rolledBack = File(newLocation).renameTo(File(oldLocation))
			if (rolledBack) {
				logger.info("Rolled back filesystem rename: $newLocation -> $oldLocation")
			} else {
				logger.error("Rollback failed; filesystem and DB are out of sync (disk=$newLocation, db=$oldLocation)")
			}
			_renameStatus.emit(false)
		}
	}

	fun updateProjectModifiedDate(location: String) =
		viewModelScope.launch(Dispatchers.IO) {
			val modifiedAt = System.currentTimeMillis()
			recentProjectDao.updateLastModified(
				location = File(location).canonicalProjectLocation(),
				lastModified = modifiedAt.toString(),
			)
			loadProjects()
		}

	fun deleteSelectedProjects(selectedLocations: List<String>) =
		viewModelScope.launch {
			if (selectedLocations.isEmpty()) {
				return@launch
			}

			var allDeletionsSucceeded = true

			try {
				withContext(Dispatchers.IO) {
					val canonicalLocations = selectedLocations.map { File(it).canonicalProjectLocation() }
					val projectsToDelete = recentProjectDao.getProjectsByLocations(canonicalLocations)
					val successfullyDeletedLocations = mutableListOf<String>()

					for (project in projectsToDelete) {
						// Delete from storage
						val isDeletedFromStorage = File(project.location).deleteRecursively()

						if (isDeletedFromStorage) {
							successfullyDeletedLocations.add(project.location)
						} else {
							logger.warn("Failed to delete project files from storage: ${project.location}")
							allDeletionsSucceeded = false
						}
					}

					if (successfullyDeletedLocations.isNotEmpty()) {
						// Delete from database
						recentProjectDao.deleteByLocations(successfullyDeletedLocations)
					}
				}

				vacuumDatabase()
				loadProjects()

				_deletionStatus.emit(allDeletionsSucceeded)
			} catch (e: Exception) {
				logger.error("An exception occurred during project deletion", e)
				_deletionStatus.emit(false)
			}
		}

	private suspend fun vacuumDatabase() {
		withContext(Dispatchers.IO) {
			runCatching {
				recentProjectDatabase.vacuum()
			}
		}
	}
}
