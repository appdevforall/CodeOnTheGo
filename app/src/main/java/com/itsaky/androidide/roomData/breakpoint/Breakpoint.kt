package com.itsaky.androidide.roomData.breakpoint

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.itsaky.androidide.roomData.recentproject.RecentProject

@Entity(
    tableName = "breakpoint_table",
    foreignKeys = [ForeignKey(
        entity = RecentProject::class,
        parentColumns = ["location"],
        childColumns = ["project_location"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Breakpoint(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Int = 0,

    /**
     * The file system path of the project this breakpoint belongs to.
     * This acts as a foreign key to the RecentProject's location.
     */
    @ColumnInfo(name = "project_location", index = true)
    val projectLocation: String,

    /**
     * The relative path of the file within the project.
     * e.g., "app/src/main/java/com/example/myapp/MainActivity.java"
     */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean,

    /**
     * The specific line number where the breakpoint is set.
     */
    @ColumnInfo(name = "line_number")
    val lineNumber: Int,
)