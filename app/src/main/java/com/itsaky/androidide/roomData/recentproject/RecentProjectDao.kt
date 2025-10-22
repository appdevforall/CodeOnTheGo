package com.itsaky.androidide.roomData.recentproject

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.io.File
import java.io.IOException
import kotlin.io.path.exists

@Dao
interface RecentProjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: RecentProject)

    @Query("DELETE FROM recent_project_table WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT * FROM recent_project_table order by last_modified DESC, create_at DESC")
    suspend fun dumpAll(): List<RecentProject>?

    @Query("SELECT * FROM recent_project_table WHERE name = :name LIMIT 1")
    suspend fun getProjectByName(name: String): RecentProject?

    @Query("SELECT * FROM recent_project_table WHERE name IN (:names)")
    suspend fun getProjectsByNames(names: List<String>): List<RecentProject>

    @Query("DELETE FROM recent_project_table")
    suspend fun deleteAll()

    @Query("DELETE FROM recent_project_table WHERE name IN (:names)")
    suspend fun deleteByNames(names: List<String>)

    @Query("UPDATE recent_project_table SET name = :newName, location = :newLocation WHERE name = :oldName")
    suspend fun updateNameAndLocation(oldName: String, newName: String, newLocation: String)

    @Query("UPDATE recent_project_table SET last_modified = :lastModified WHERE name = :projectName")
    suspend fun updateLastModified(projectName: String, lastModified: String)

    @Query("SELECT COUNT(*) FROM recent_project_table")
    suspend fun getCount(): Int

    /**
     * Deletes projects from the database and their corresponding files from storage
     * within a single transaction. If file deletion fails, the database
     * operation will be rolled back.
     */
    @Transaction
    suspend fun deleteProjectsAndFiles(names: List<String>) {
        val projectsToDelete = getProjectsByNames(names)

        // Delete files from device storage
        for (project in projectsToDelete) {
            val projectDir = File(project.location)
            if (projectDir.exists() && !projectDir.deleteRecursively()) {
                throw IOException("Failed to delete project directory: ${project.location}")
            }
        }

        // Delete from the database
        deleteByNames(names)
    }
}
