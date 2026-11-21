package com.itsaky.androidide.roomData.recentproject

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The SQLite debugger endpoint of one app built from a project (ADFA-7131). [token], [port] and
 * [updatedAt] stay null until the app reports them. They go back to null only when a Run install
 * from ProjectHandlerActivity succeeds, its result names the package, and it arrives in the same
 * Code on the Go process. Some installs keep the old token and port until the app reports again:
 * a Quick Build install (the proxy app, installed under the project's application ID, writes no
 * install note), a Run install with no project open, a Run install whose APK's application ID
 * cannot be read (neither writes an install note), a success result with no package name, and a
 * result that arrives after Code on the Go restarted. LaunchAppAction pairs the last four at the
 * next launch from a project and keeps the stored values.
 *
 * ON UPDATE CASCADE as well as ON DELETE: renaming a project rewrites its recent_project_table
 * location, which would otherwise be refused while foreign keys are on.
 */
@Entity(
	tableName = "debugger_endpoint_table",
	foreignKeys = [
		ForeignKey(
			entity = RecentProject::class,
			parentColumns = ["location"],
			childColumns = ["project_location"],
			onDelete = ForeignKey.CASCADE,
			onUpdate = ForeignKey.CASCADE,
		),
	],
	indices = [Index(value = ["project_location"])],
)
data class DebuggerEndpoint(
	@PrimaryKey
	@ColumnInfo(name = "application_id") val applicationId: String,
	@ColumnInfo(name = "project_location") val projectLocation: String,
	@ColumnInfo(name = "token") val token: String? = null,
	@ColumnInfo(name = "port") val port: Int? = null,
	@ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
)
