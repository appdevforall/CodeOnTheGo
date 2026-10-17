package com.itsaky.androidide.roomData.recentproject

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_project_maintenance")
data class RecentProjectMaintenance(
	@PrimaryKey
	@ColumnInfo(name = "key") val key: String,
	@ColumnInfo(name = "completed") val completed: Boolean,
)
