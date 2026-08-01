package org.appdevforall.cotg.quickbuild.domain.annotations

import java.io.File

/**
 * A minimal but realistically-shaped Room + Hilt app, materialized on disk so the
 * analyzer runs against real files the way a session does.
 *
 * Deliberately covers the shapes that make annotation-aware classification hard:
 * an `@Entity` with an `@Embedded` value type that carries NO annotation of its own,
 * a non-annotated base class an entity inherits fields from, a `@TypeConverters`
 * converter, `@Dao` queries whose SQL lives in annotation arguments, and plain UI
 * files (Activity, ViewModel) that should never have to rebaseline.
 */
class RoomAppFixture(
	root: File,
) {
	private val sourceDir = File(root, "app/src/main/java/com/example/notes").apply { mkdirs() }

	val user = write("User.kt", USER)
	val address = write("Address.kt", ADDRESS)
	val baseEntity = write("BaseEntity.kt", BASE_ENTITY)
	val converters = write("Converters.kt", CONVERTERS)
	val userDao = write("UserDao.kt", USER_DAO)
	val database = write("AppDatabase.kt", DATABASE)
	val viewModel = write("UserViewModel.kt", VIEW_MODEL)
	val activity = write("MainActivity.kt", ACTIVITY)
	val formatter = write("Formatter.kt", FORMATTER)

	val all: List<File> =
		listOf(user, address, baseEntity, converters, userDao, database, viewModel, activity, formatter)

	fun write(
		name: String,
		text: String,
	): File = File(sourceDir, name).apply { writeText(text) }

	/** Overwrites an existing fixture file, simulating a save. */
	fun edit(
		file: File,
		text: String,
	) {
		file.writeText(text)
	}

	companion object {
		val USER =
			"""
			package com.example.notes

			import androidx.room.Embedded
			import androidx.room.Entity
			import androidx.room.PrimaryKey

			@Entity(tableName = "users")
			data class User(
				@PrimaryKey val id: Long,
				val name: String,
				@Embedded val address: Address,
			) : BaseEntity()
			""".trimIndent()

		/** A plain data class an `@Embedded` property points at - no annotation of its own. */
		val ADDRESS =
			"""
			package com.example.notes

			data class Address(
				val street: String,
				val city: String,
			)
			""".trimIndent()

		/** Room reads inherited fields; this base class has no annotation either. */
		val BASE_ENTITY =
			"""
			package com.example.notes

			abstract class BaseEntity {
				var createdAt: Long = 0
			}
			""".trimIndent()

		val CONVERTERS =
			"""
			package com.example.notes

			import androidx.room.TypeConverter

			class Converters {
				@TypeConverter
				fun fromTimestamp(value: Long?): String? {
					return value?.toString()
				}
			}
			""".trimIndent()

		val USER_DAO =
			"""
			package com.example.notes

			import androidx.room.Dao
			import androidx.room.Insert
			import androidx.room.Query

			@Dao
			interface UserDao {
				@Query("SELECT * FROM users ORDER BY name")
				fun all(): List<User>

				@Insert
				fun insert(user: User)
			}
			""".trimIndent()

		val DATABASE =
			"""
			package com.example.notes

			import androidx.room.Database
			import androidx.room.RoomDatabase
			import androidx.room.TypeConverters

			@Database(entities = [User::class], version = 1)
			@TypeConverters(Converters::class)
			abstract class AppDatabase : RoomDatabase() {
				abstract fun userDao(): UserDao
			}
			""".trimIndent()

		val VIEW_MODEL =
			"""
			package com.example.notes

			class UserViewModel(
				private val dao: UserDao,
			) {
				fun greeting(): String {
					val count = dao.all().size
					return "You have " + count + " users"
				}
			}
			""".trimIndent()

		val ACTIVITY =
			"""
			package com.example.notes

			import android.app.Activity
			import android.os.Bundle

			class MainActivity : Activity() {
				override fun onCreate(savedInstanceState: Bundle?) {
					super.onCreate(savedInstanceState)
					setTitle("Notes")
				}
			}
			""".trimIndent()

		val FORMATTER =
			"""
			package com.example.notes

			object Formatter {
				fun format(name: String): String {
					return name.trim()
				}
			}
			""".trimIndent()
	}
}
