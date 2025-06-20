package com.itsaky.androidide.idetooltips

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ContentTypes")
data class ContentType(
    @PrimaryKey(autoGenerate = true) val id: Int? = 0,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "compression") val compression: String
)
