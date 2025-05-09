package com.itsaky.androidide.idetooltips

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

// Define the data entities (same as before)
@Entity(
    tableName = "Content",
    primaryKeys = ["path", "languageID"],
    foreignKeys = [
        ForeignKey(entity = Language::class, parentColumns = ["id"], childColumns = ["languageID"]),
        ForeignKey(entity = ContentType::class, parentColumns = ["id"], childColumns = ["contentTypeID"])
    ]
)
data class Content(
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "languageID") val languageID: Int,
    @ColumnInfo(name = "content") val content: ByteArray,
    @ColumnInfo(name = "contentTypeID") val contentTypeID: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Content

        if (languageID != other.languageID) return false
        if (contentTypeID != other.contentTypeID) return false
        if (path != other.path) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = languageID
        result = 31 * result + contentTypeID
        result = 31 * result + path.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}
