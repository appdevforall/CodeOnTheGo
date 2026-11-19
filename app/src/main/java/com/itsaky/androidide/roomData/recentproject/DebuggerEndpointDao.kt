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

	/** Clears what [applicationId] reported, once an install has replaced the process that reported it. */
	@Query("UPDATE debugger_endpoint_table SET token = NULL, port = NULL, updated_at = NULL WHERE application_id = :applicationId")
	suspend fun clearEndpoint(applicationId: String)

	/**
	 * Pairs the just-installed [applicationId] with [projectLocation] and clears its token and port,
	 * in one transaction: the install replaced the process they came from, whichever project built
	 * it. Not REPLACE, which would delete the row. Statements rather than ON CONFLICT DO UPDATE,
	 * which needs SQLite 3.24 and minSdk 28 ships 3.22.
	 *
	 * Throws SQLiteConstraintException when [projectLocation] has no recent_project_table row.
	 */
	@Transaction
	suspend fun upsertPairing(
		applicationId: String,
		projectLocation: String,
	) {
		insertPairingIfAbsent(applicationId, projectLocation)
		clearEndpoint(applicationId)
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
