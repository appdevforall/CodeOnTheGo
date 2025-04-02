package com.itsaky.androidide.roomData.recentproject

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_project_table")
data class RecentProject(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "create_at") val createdAt: String,
    @ColumnInfo(name = "location") val location: String,
)

