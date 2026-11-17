package com.itsaky.androidide.di

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.manager.services.DebuggerEndpointSource
import com.itsaky.androidide.repositories.DebuggerEndpointRepository
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.sqlitedebugger.DebuggerEndpointValidation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** The SQLite debugger bindings of [coreModule], resolved as the app resolves them, over an in-memory database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DebuggerEndpointBindingsTest {
	private val app: Application get() = ApplicationProvider.getApplicationContext()
	private lateinit var room: RecentProjectRoomDatabase
	private lateinit var koin: Koin
	private lateinit var project: File

	@Before
	fun setUp() {
		room =
			Room
				.inMemoryDatabaseBuilder(app, RecentProjectRoomDatabase::class.java)
				.allowMainThreadQueries()
				.build()
		koin =
			koinApplication {
				androidContext(app)
				allowOverride(true)
				modules(coreModule, module { single { room } })
			}.koin
		project = Files.createTempDirectory("proj").toFile()
		runBlocking {
			room.recentProjectDao().insert(RecentProject(name = "p", createdAt = "1", location = project.canonicalPath))
		}
	}

	@After
	fun tearDown() {
		koin.close()
		room.close()
		project.deleteRecursively()
	}

	private suspend fun report(
		applicationId: String,
		token: String,
	) {
		val repository = koin.get<DebuggerEndpointRepository>()
		assertThat(repository.recordInstalled(applicationId, project.canonicalPath)).isTrue()
		assertThat(repository.storeEndpoint(applicationId, DebuggerEndpointValidation.Endpoint(token, 5555))).isTrue()
	}

	@Test
	fun `the source plugins read returns what the bound repository stored`() =
		runBlocking<Unit> {
			report(app.packageName, "tok-en_1")

			val endpoints = koin.get<DebuggerEndpointSource>().endpointsFor(project.path)

			assertThat(endpoints.map { it.applicationId }).containsExactly(app.packageName)
			assertThat(endpoints.single().token).isEqualTo("tok-en_1")
		}
}
