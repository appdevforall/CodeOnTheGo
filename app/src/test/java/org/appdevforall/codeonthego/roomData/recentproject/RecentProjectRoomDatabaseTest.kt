package org.appdevforall.codeonthego.roomData.recentproject

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
