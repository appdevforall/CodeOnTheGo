package com.itsaky.androidide.roomData.recentproject

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DebuggerEndpointDao {
	@Query("INSERT OR IGNORE INTO debugger_endpoint_table (application_id, project_location) VALUES (:applicationId, :projectLocation)")
	suspend fun insertPairingIfAbsent(
		applicationId: String,
		projectLocation: String,
	)

	@Query("UPDATE debugger_endpoint_table SET project_location = :projectLocation WHERE application_id = :applicationId")
	suspend fun updatePairingLocation(
		applicationId: String,
		projectLocation: String,
	)

	/** Clears what the old project's build reported, when [applicationId] moves to another project. */
	@Query(
		"UPDATE debugger_endpoint_table SET token = NULL, port = NULL, updated_at = NULL " +
			"WHERE application_id = :applicationId AND project_location != :projectLocation",
	)
	suspend fun clearEndpointIfProjectDiffers(
		applicationId: String,
		projectLocation: String,
	)

	/**
	 * Pairs [applicationId] with [projectLocation], keeping any token and port already stored
	 * unless the app was paired with a different project, whose build they belong to.
	 * Not REPLACE, which would delete the row and lose them. Two statements rather than
	 * ON CONFLICT DO UPDATE, which needs SQLite 3.24 and minSdk 28 ships 3.22.
	 *
	 * Throws SQLiteConstraintException when [projectLocation] has no recent_project_table row.
	 */
	@Transaction
	suspend fun upsertPairing(
		applicationId: String,
		projectLocation: String,
	) {
		insertPairingIfAbsent(applicationId, projectLocation)
		clearEndpointIfProjectDiffers(applicationId, projectLocation)
		updatePairingLocation(applicationId, projectLocation)
	}

	/** Returns the number of rows changed: 0 when [applicationId] has no pairing. */
	@Query("UPDATE debugger_endpoint_table SET token = :token, port = :port, updated_at = :updatedAt WHERE application_id = :applicationId")
	suspend fun updateEndpoint(
		applicationId: String,
		token: String,
		port: Int,
		updatedAt: Long,
	): Int

	@Query("SELECT * FROM debugger_endpoint_table WHERE project_location = :projectLocation")
	suspend fun getByProjectLocation(projectLocation: String): List<DebuggerEndpoint>

	@Query("SELECT * FROM debugger_endpoint_table WHERE application_id = :applicationId")
	suspend fun getByApplicationId(applicationId: String): DebuggerEndpoint?
}
