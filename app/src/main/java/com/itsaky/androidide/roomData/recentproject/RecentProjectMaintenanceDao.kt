package com.itsaky.androidide.roomData.recentproject

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecentProjectMaintenanceDao {
	@Query("SELECT completed FROM recent_project_maintenance WHERE `key` = :key LIMIT 1")
	suspend fun isCompleted(key: String): Boolean?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun setCompleted(maintenance: RecentProjectMaintenance)
}
