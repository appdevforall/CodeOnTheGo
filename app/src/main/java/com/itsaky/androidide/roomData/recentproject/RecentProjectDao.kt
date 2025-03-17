package com.itsaky.androidide.roomData.recentproject

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecentProjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: RecentProject)

    @Query("DELETE FROM recent_project_table WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT * FROM recent_project_table order by create_at DESC")
    suspend fun dumpAll(): List<RecentProject>?

    @Query("DELETE FROM recent_project_table")
    suspend fun deleteAll()

    @Query("DELETE FROM recent_project_table WHERE name IN (:names)")
    suspend fun deleteByNames(names: List<String>)

    @Query("SELECT COUNT(*) FROM recent_project_table")
    suspend fun getCount(): Int
}
