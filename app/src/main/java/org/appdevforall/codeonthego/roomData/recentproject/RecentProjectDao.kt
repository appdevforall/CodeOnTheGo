package org.appdevforall.codeonthego.roomData.recentproject

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RecentProjectDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(project: RecentProject)

	@Update
	suspend fun update(project: RecentProject)

	@Query("DELETE FROM recent_project_table WHERE id IN (:ids)")
	suspend fun deleteByIds(ids: List<Int>)

	@Query("DELETE FROM recent_project_table WHERE location = :location")
	suspend fun deleteByLocation(location: String)

	@Query("SELECT * FROM recent_project_table order by last_modified DESC, create_at DESC")
	suspend fun dumpAll(): List<RecentProject>?

	@Query("SELECT * FROM recent_project_table WHERE location = :location LIMIT 1")
	suspend fun getProjectByLocation(location: String): RecentProject?

	@Query("SELECT * FROM recent_project_table WHERE location IN (:locations)")
	suspend fun getProjectsByLocations(locations: List<String>): List<RecentProject>

	@Query("DELETE FROM recent_project_table")
	suspend fun deleteAll()

	@Query("DELETE FROM recent_project_table WHERE location IN (:locations)")
	suspend fun deleteByLocations(locations: List<String>)

	@Query("UPDATE recent_project_table SET name = :newName, location = :newLocation WHERE location = :oldLocation")
	suspend fun updateNameAndLocation(
		oldLocation: String,
		newName: String,
		newLocation: String,
	)

	@Query("UPDATE recent_project_table SET last_modified = :lastModified WHERE location = :location")
	suspend fun updateLastModified(
		location: String,
		lastModified: String,
	)

	@Query("UPDATE recent_project_table SET language = :language WHERE location = :location")
	suspend fun updateLanguage(
		location: String,
		language: String,
	)

	@Query("SELECT COUNT(*) FROM recent_project_table")
	suspend fun getCount(): Int
}
