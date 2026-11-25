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
 * cannot be read (neither writes an install note), a success result with no package name, a
 * result that arrives after Code on the Go restarted, and an install by the intent-based fallback
 * installer (on MIUI, or when debugFallbackInstaller is set; Run installs too), which never calls
 * beforeCommit and has no session or result. Only the Launch action (LaunchAppAction) pairs an
 * unpaired app installed by any of the six, at the next launch from a project, and keeps an
 * already-paired app's stored values; the automatic post-install launch
 * (BaseEditorActivity.doLaunchApp, in debug mode too), RestartVmAction and Quick Build's launch of
 * the proxy app never call recordLaunched. Other installs keep them too: recordInstalled returns
 * false and keeps the old values when the project has no recent-project row or the write fails; a
 * result that names a different package is dropped without clearing; a beforeCommit that throws is
 * logged and the install goes on with no note; and an install not made from a project (the file
 * tab, adb) leaves an already-paired app listed with its dead token and port.
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
