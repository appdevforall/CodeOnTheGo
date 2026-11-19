package com.itsaky.androidide.roomData.recentproject

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RecentProjectRoomDatabaseTest {
	@Test
	fun `migration 4 to 5 creates maintenance table`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val config =
			SupportSQLiteOpenHelper.Configuration
				.builder(context)
				.name(null) // in-memory database
				.callback(
					object : SupportSQLiteOpenHelper.Callback(4) {
						override fun onCreate(db: SupportSQLiteDatabase) {}

						override fun onUpgrade(
							db: SupportSQLiteDatabase,
							oldVersion: Int,
							newVersion: Int,
						) {}
					},
				).build()

		val helper = FrameworkSQLiteOpenHelperFactory().create(config)
		val db = helper.writableDatabase

		// Create a mock table for v4 to make sure we're starting clean
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `recent_project_table` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `location` TEXT NOT NULL, `last_modified` TEXT NOT NULL DEFAULT '0', `template_name` TEXT NOT NULL DEFAULT 'unknown', `language` TEXT NOT NULL DEFAULT 'unknown')",
		)

		// Run the migration
		RecentProjectRoomDatabase.migration4To5.migrate(db)

		// Verify that the table was created by checking sqlite_master
		val cursor =
			db.query(
				"SELECT name FROM sqlite_master WHERE type='table' AND name='recent_project_maintenance'",
			)

		var tableFound = false
		if (cursor.moveToFirst()) {
			val name = cursor.getString(0)
			tableFound = name == "recent_project_maintenance"
		}
		cursor.close()
		db.close()

		assertThat(tableFound).isTrue()
	}

	private fun createVersion5Database(name: String): File {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val file = context.getDatabasePath(name)
		file.parentFile?.mkdirs()
		file.delete()
		val db = SQLiteDatabase.openOrCreateDatabase(file, null)
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `recent_project_table` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `create_at` TEXT NOT NULL, `location` TEXT NOT NULL, `last_modified` TEXT NOT NULL DEFAULT '0', `template_name` TEXT NOT NULL DEFAULT 'unknown', `language` TEXT NOT NULL DEFAULT 'unknown')",
		)
		db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_project_table_location` ON `recent_project_table` (`location`)")
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `recent_project_maintenance` (`key` TEXT NOT NULL, `completed` INTEGER NOT NULL, PRIMARY KEY(`key`))",
		)
		db.execSQL(
			"INSERT INTO recent_project_table (name, create_at, location, last_modified, template_name, language) VALUES ('A', '1', '/p/a', '10', 'Basic', 'Kotlin')",
		)
		db.execSQL(
			"INSERT INTO recent_project_table (name, create_at, location, last_modified, template_name, language) VALUES ('B', '2', '/p/b', '20', 'Empty', 'Java')",
		)
		db.version = 5
		db.close()
		return file
	}

	@Test
	fun `migration 5 to 6 keeps recent projects and creates the endpoint table with its foreign key and index`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val name = "migration_5_6_test.db"
		val file = createVersion5Database(name)

		// Room validates the migrated schema (columns, foreign keys, indices) against the entities
		// when it opens the database, and throws if the migration SQL differs from what it expects.
		val room =
			Room
				.databaseBuilder(context, RecentProjectRoomDatabase::class.java, name)
				.addMigrations(RecentProjectRoomDatabase.migration5To6)
				.allowMainThreadQueries()
				.build()
		try {
			val projects = runBlocking { room.recentProjectDao().dumpAll() }.orEmpty()
			assertThat(projects.map { it.location to it.name }).containsExactly("/p/b" to "B", "/p/a" to "A").inOrder()
			assertThat(projects.map { it.language }).containsExactly("Java", "Kotlin").inOrder()

			val db = room.openHelper.writableDatabase
			db.query("PRAGMA foreign_key_list(`debugger_endpoint_table`)").use { c ->
				assertThat(c.count).isEqualTo(1)
				c.moveToFirst()
				assertThat(c.getString(c.getColumnIndexOrThrow("table"))).isEqualTo("recent_project_table")
				assertThat(c.getString(c.getColumnIndexOrThrow("from"))).isEqualTo("project_location")
				assertThat(c.getString(c.getColumnIndexOrThrow("to"))).isEqualTo("location")
				assertThat(c.getString(c.getColumnIndexOrThrow("on_delete"))).isEqualTo("CASCADE")
			}
			db
				.query(
					"SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='debugger_endpoint_table' AND name='index_debugger_endpoint_table_project_location'",
				).use { c ->
					assertThat(c.count).isEqualTo(1)
				}

			runBlocking {
				room.debuggerEndpointDao().upsertPairing("com.example.one", "/p/a")
				room.debuggerEndpointDao().upsertPairing("com.example.two", "/p/b")
				room.recentProjectDao().deleteByLocation("/p/a")
				assertThat(room.debuggerEndpointDao().getByApplicationId("com.example.one")).isNull()
				assertThat(room.debuggerEndpointDao().getByApplicationId("com.example.two")).isNotNull()
			}
		} finally {
			room.close()
			file.delete()
		}
	}

	@Test
	fun `getDatabase registers the 5 to 6 migration`() {
		assertThat(RecentProjectRoomDatabase.migrations.map { it.startVersion to it.endVersion }).contains(5 to 6)
		assertThat(RecentProjectRoomDatabase.migrations).asList().contains(RecentProjectRoomDatabase.migration5To6)
	}

	private fun newDatabase(): RecentProjectRoomDatabase =
		Room
			.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecentProjectRoomDatabase::class.java)
			.allowMainThreadQueries()
			.build()

	private fun project(location: String) = RecentProject(name = location, createdAt = "1", location = location)

	@Test
	fun `deleting a recent project deletes its endpoint rows`() =
		runBlocking<Unit> {
			val room = newDatabase()
			try {
				room.recentProjectDao().insert(project("/p/a"))
				room.recentProjectDao().insert(project("/p/b"))
				val dao = room.debuggerEndpointDao()
				dao.upsertPairing("com.example.one", "/p/a")
				dao.upsertPairing("com.example.two", "/p/a")
				dao.upsertPairing("com.example.three", "/p/b")

				room.recentProjectDao().deleteByLocation("/p/a")

				assertThat(dao.getByProjectLocation("/p/a")).isEmpty()
				assertThat(dao.getByProjectLocation("/p/b").map { it.applicationId }).containsExactly("com.example.three")
			} finally {
				room.close()
			}
		}

	@Test
	fun `renaming a recent project moves its endpoint rows`() =
		runBlocking<Unit> {
			val room = newDatabase()
			try {
				room.recentProjectDao().insert(project("/p/a"))
				val dao = room.debuggerEndpointDao()
				dao.upsertPairing("com.example.one", "/p/a")

				room.recentProjectDao().updateNameAndLocation("/p/a", "renamed", "/p/renamed")

				assertThat(dao.getByProjectLocation("/p/renamed").map { it.applicationId }).containsExactly("com.example.one")
			} finally {
				room.close()
			}
		}

	@Test
	fun `upsertPairing for a different project moves it and clears token, port and time`() =
		runBlocking<Unit> {
			val room = newDatabase()
			try {
				room.recentProjectDao().insert(project("/p/a"))
				room.recentProjectDao().insert(project("/p/b"))
				val dao = room.debuggerEndpointDao()
				dao.upsertPairing("com.example.one", "/p/a")
				assertThat(dao.updateEndpoint("com.example.one", "tok", 4242, 99L)).isEqualTo(1)

				dao.upsertPairing("com.example.one", "/p/b")

				val row = dao.getByApplicationId("com.example.one")
				assertThat(row).isEqualTo(DebuggerEndpoint("com.example.one", "/p/b", null, null, null))
			} finally {
				room.close()
			}
		}

	@Test
	fun `upsertPairing for the same project also clears token, port and time`() =
		runBlocking<Unit> {
			val room = newDatabase()
			try {
				room.recentProjectDao().insert(project("/p/a"))
				val dao = room.debuggerEndpointDao()
				dao.upsertPairing("com.example.one", "/p/a")
				dao.updateEndpoint("com.example.one", "tok", 4242, 99L)

				dao.upsertPairing("com.example.one", "/p/a")

				assertThat(dao.getByApplicationId("com.example.one"))
					.isEqualTo(DebuggerEndpoint("com.example.one", "/p/a", null, null, null))
			} finally {
				room.close()
			}
		}

	@Test
	fun `updateEndpoint matches no row for an unknown application`() =
		runBlocking<Unit> {
			val room = newDatabase()
			try {
				assertThat(room.debuggerEndpointDao().updateEndpoint("com.unknown", "tok", 1, 1L)).isEqualTo(0)
				assertThat(room.debuggerEndpointDao().getByApplicationId("com.unknown")).isNull()
			} finally {
				room.close()
			}
		}

	@Test
	fun `upsertPairing for a project that is not in recents is refused`() =
		runBlocking<Unit> {
			val room = newDatabase()
			try {
				val result = runCatching { room.debuggerEndpointDao().upsertPairing("com.example.one", "/p/missing") }
				assertThat(result.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)
				assertThat(room.debuggerEndpointDao().getByApplicationId("com.example.one")).isNull()
			} finally {
				room.close()
			}
		}

	@Test
	fun `insertPairingIfAbsent leaves an existing pairing and its endpoint alone`() =
		runBlocking<Unit> {
			val room = newDatabase()
			try {
				room.recentProjectDao().insert(project("/p/a"))
				room.recentProjectDao().insert(project("/p/b"))
				val dao = room.debuggerEndpointDao()
				dao.upsertPairing("com.example.one", "/p/a")
				dao.updateEndpoint("com.example.one", "tok", 4242, 99L)

				dao.insertPairingIfAbsent("com.example.one", "/p/b")

				assertThat(dao.getByApplicationId("com.example.one"))
					.isEqualTo(DebuggerEndpoint("com.example.one", "/p/a", "tok", 4242, 99L))
			} finally {
				room.close()
			}
		}
}
