package com.itsaky.androidide.repositories

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.roomData.recentproject.DebuggerEndpoint
import com.itsaky.androidide.roomData.recentproject.DebuggerEndpointDao
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.sqlitedebugger.DebuggerEndpointValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** The database path DebuggerEndpointReceiver and the install/launch hooks call. */
@RunWith(RobolectricTestRunner::class)
class DebuggerEndpointRepositoryTest {
	private lateinit var room: RecentProjectRoomDatabase
	private lateinit var repository: DebuggerEndpointRepository

	@Before
	fun setUp() {
		room =
			Room
				.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecentProjectRoomDatabase::class.java)
				.allowMainThreadQueries()
				.build()
		repository = DebuggerEndpointRepository(room.debuggerEndpointDao(), CoroutineScope(Dispatchers.IO), clock = { 1234L })
	}

	@After
	fun tearDown() = room.close()

	private suspend fun addProject(location: String) =
		room.recentProjectDao().insert(RecentProject(name = location, createdAt = "1", location = location))

	private val endpoint = DebuggerEndpointValidation.Endpoint("tok-en_1", 5555)

	@Test
	fun `a report for a paired app stores token, port and time`() =
		runBlocking<Unit> {
			addProject("/p/a")
			assertThat(repository.recordInstalled("com.example", "/p/a")).isTrue()

			assertThat(repository.storeEndpoint("com.example", endpoint)).isTrue()

			val row = room.debuggerEndpointDao().getByApplicationId("com.example")!!
			assertThat(row.token).isEqualTo("tok-en_1")
			assertThat(row.port).isEqualTo(5555)
			assertThat(row.updatedAt).isEqualTo(1234L)
		}

	@Test
	fun `a report from an unpaired app stores nothing`() =
		runBlocking<Unit> {
			addProject("/p/a")

			assertThat(repository.storeEndpoint("com.stranger", endpoint)).isFalse()

			assertThat(room.debuggerEndpointDao().getByApplicationId("com.stranger")).isNull()
		}

	@Test
	fun `pairing a project that is not in recents is skipped without throwing`() =
		runBlocking<Unit> {
			assertThat(repository.recordInstalled("com.example", "/p/missing")).isFalse()

			assertThat(room.debuggerEndpointDao().getByApplicationId("com.example")).isNull()
		}

	@Test
	fun `relaunching keeps the stored token and port`() =
		runBlocking<Unit> {
			addProject("/p/a")
			repository.recordInstalled("com.example", "/p/a")
			repository.storeEndpoint("com.example", endpoint)

			repository.recordInstalled("com.example", "/p/a")

			val row = room.debuggerEndpointDao().getByApplicationId("com.example")!!
			assertThat(row.port).isEqualTo(5555)
			assertThat(row.token).isEqualTo("tok-en_1")
			assertThat(row.updatedAt).isEqualTo(1234L)
		}

	@Test
	fun `re-pairing with a different project clears token, port and time`() =
		runBlocking<Unit> {
			addProject("/p/a")
			addProject("/p/b")
			repository.recordInstalled("com.example", "/p/a")
			repository.storeEndpoint("com.example", endpoint)

			assertThat(repository.recordInstalled("com.example", "/p/b")).isTrue()

			val row = room.debuggerEndpointDao().getByApplicationId("com.example")!!
			assertThat(row.projectLocation).isEqualTo("/p/b")
			assertThat(row.token).isNull()
			assertThat(row.port).isNull()
			assertThat(row.updatedAt).isNull()
		}

	@Test
	fun `an expected install is paired with its canonical project location once it succeeds`() =
		runBlocking<Unit> {
			val dir =
				java.nio.file.Files
					.createTempDirectory("proj")
					.toFile()
			try {
				addProject(dir.canonicalPath)

				repository.expectInstallAsync(File(dir, "../" + dir.name)) { "com.example" }
				var confirmed = false
				repeat(100) {
					if (!confirmed) {
						confirmed = repository.confirmInstall("com.example")
						if (!confirmed) Thread.sleep(50)
					}
				}

				assertThat(confirmed).isTrue()
				assertThat(room.debuggerEndpointDao().getByApplicationId("com.example")?.projectLocation).isEqualTo(dir.canonicalPath)
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `an install nobody expected is not paired`() =
		runBlocking<Unit> {
			addProject("/p/a")

			assertThat(repository.confirmInstall("com.example")).isFalse()

			assertThat(room.debuggerEndpointDao().getByApplicationId("com.example")).isNull()
		}

	@Test
	fun `an expected install writes nothing until it is confirmed`() =
		runBlocking<Unit> {
			val dir =
				java.nio.file.Files
					.createTempDirectory("proj")
					.toFile()
			try {
				addProject(dir.canonicalPath)

				repository.expectInstallAsync(dir) { "com.example" }
				Thread.sleep(500)

				assertThat(room.debuggerEndpointDao().getByApplicationId("com.example")).isNull()
			} finally {
				dir.delete()
			}
		}

	private suspend fun awaitNoted(
		repo: DebuggerEndpointRepository,
		id: String,
		dir: File,
		discard: Boolean,
		advanceMillis: Long,
		now: AtomicLong,
	): Boolean {
		repo.expectInstallAsync(dir) { id }
		Thread.sleep(300)
		if (discard) repo.discardPendingInstall(id)
		now.addAndGet(advanceMillis)
		return repo.confirmInstall(id)
	}

	private fun clockedRepository(now: AtomicLong) =
		DebuggerEndpointRepository(room.debuggerEndpointDao(), CoroutineScope(Dispatchers.IO), clock = { now.get() })

	@Test
	fun `a failed install does not pair a later install of the same app`() =
		runBlocking<Unit> {
			val dir =
				File.createTempFile("proj", "").apply {
					delete()
					mkdirs()
				}
			try {
				addProject(dir.canonicalPath)
				val now = AtomicLong(1_000L)

				val paired = awaitNoted(clockedRepository(now), "com.example", dir, discard = true, advanceMillis = 0, now = now)

				assertThat(paired).isFalse()
				assertThat(room.debuggerEndpointDao().getByApplicationId("com.example")).isNull()
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `a note older than the expiry is not used`() =
		runBlocking<Unit> {
			val dir =
				File.createTempFile("proj", "").apply {
					delete()
					mkdirs()
				}
			try {
				addProject(dir.canonicalPath)
				val now = AtomicLong(1_000L)

				val sixMinutes = 6 * 60 * 1000L
				val paired = awaitNoted(clockedRepository(now), "com.example", dir, discard = false, advanceMillis = sixMinutes, now = now)

				assertThat(paired).isFalse()
				assertThat(room.debuggerEndpointDao().getByApplicationId("com.example")).isNull()
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `a recent note still pairs on success`() =
		runBlocking<Unit> {
			val dir =
				File.createTempFile("proj", "").apply {
					delete()
					mkdirs()
				}
			try {
				addProject(dir.canonicalPath)
				val now = AtomicLong(1_000L)

				val paired = awaitNoted(clockedRepository(now), "com.example", dir, discard = false, advanceMillis = 60_000L, now = now)

				assertThat(paired).isTrue()
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `launching pairs an unpaired app`() =
		runBlocking<Unit> {
			addProject("/p/a")

			assertThat(repository.recordLaunched("com.example", "/p/a")).isTrue()

			assertThat(room.debuggerEndpointDao().getByApplicationId("com.example")?.projectLocation).isEqualTo("/p/a")
		}

	@Test
	fun `launching from another project neither moves nor clears an existing pairing`() =
		runBlocking<Unit> {
			addProject("/p/a")
			addProject("/p/b")
			repository.recordInstalled("com.example", "/p/a")
			repository.storeEndpoint("com.example", endpoint)

			repository.recordLaunched("com.example", "/p/b")

			val row = room.debuggerEndpointDao().getByApplicationId("com.example")!!
			assertThat(row.projectLocation).isEqualTo("/p/a")
			assertThat(row.token).isEqualTo("tok-en_1")
			assertThat(row.port).isEqualTo(5555)
		}

	@Test
	fun `launching a project that is not in recents is skipped without throwing`() =
		runBlocking<Unit> {
			assertThat(repository.recordLaunched("com.example", "/p/missing")).isFalse()
		}

	private class ThrowingDao(
		private val error: () -> Exception,
	) : DebuggerEndpointDao {
		override suspend fun insertPairingIfAbsent(
			applicationId: String,
			projectLocation: String,
		): Unit = throw error()

		override suspend fun updatePairingLocation(
			applicationId: String,
			projectLocation: String,
		): Unit = throw error()

		override suspend fun clearEndpointIfProjectDiffers(
			applicationId: String,
			projectLocation: String,
		): Unit = throw error()

		override suspend fun upsertPairing(
			applicationId: String,
			projectLocation: String,
		): Unit = throw error()

		override suspend fun updateEndpoint(
			applicationId: String,
			token: String,
			port: Int,
			updatedAt: Long,
		): Int = throw error()

		override suspend fun getByProjectLocation(projectLocation: String): List<DebuggerEndpoint> = throw error()

		override suspend fun getByApplicationId(applicationId: String): DebuggerEndpoint? = throw error()
	}

	@Test
	fun `any database failure is reported as false, not thrown`() =
		runBlocking<Unit> {
			for (error in listOf<() -> Exception>(
				{ IllegalStateException("closed") },
				{ android.database.SQLException("generic") },
			)) {
				val failing = DebuggerEndpointRepository(ThrowingDao(error), CoroutineScope(Dispatchers.IO))

				assertThat(failing.recordInstalled("com.example", "/p/a")).isFalse()
				assertThat(failing.recordLaunched("com.example", "/p/a")).isFalse()
				assertThat(failing.storeEndpoint("com.example", endpoint)).isFalse()
			}
		}

	@Test
	fun `cancellation is not swallowed`() {
		val failing =
			DebuggerEndpointRepository(
				ThrowingDao { kotlinx.coroutines.CancellationException("cancelled") },
				CoroutineScope(Dispatchers.IO),
			)

		val result = runCatching { runBlocking { failing.storeEndpoint("com.example", endpoint) } }

		assertThat(result.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
	}
}
