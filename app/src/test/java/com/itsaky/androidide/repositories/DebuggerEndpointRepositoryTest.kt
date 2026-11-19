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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** The database path DebuggerEndpointReceiver and the install/launch hooks call. */
@RunWith(RobolectricTestRunner::class)
class DebuggerEndpointRepositoryTest {
	private lateinit var room: RecentProjectRoomDatabase
	private lateinit var repository: DebuggerEndpointRepository

	/** The application IDs the repository asked to report again, in order. */
	private val announced = CopyOnWriteArrayList<String>()

	@Before
	fun setUp() {
		room =
			Room
				.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RecentProjectRoomDatabase::class.java)
				.allowMainThreadQueries()
				.build()
		repository =
			DebuggerEndpointRepository(
				room.debuggerEndpointDao(),
				CoroutineScope(Dispatchers.IO),
				clock = { 1234L },
				isInstalled = { true },
				announce = { announced += it },
			)
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

			repository.recordLaunched("com.example", "/p/a")

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

	/** A project directory that exists and is in recents. */
	private suspend fun newProject(): File =
		java.nio.file.Files
			.createTempDirectory("proj")
			.toFile()
			.also { addProject(it.canonicalPath) }

	private suspend fun pairedLocation(applicationId: String = "com.example"): String? =
		room.debuggerEndpointDao().getByApplicationId(applicationId)?.projectLocation

	private fun clockedRepository(now: AtomicLong) =
		DebuggerEndpointRepository(
			room.debuggerEndpointDao(),
			CoroutineScope(Dispatchers.IO),
			clock = { now.get() },
			isInstalled = { true },
			announce = { announced += it },
		)

	@Test
	fun `a noted install is paired with its canonical project location once its session succeeds`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				repository.notePendingInstall(1, File(dir, "../" + dir.name), "com.example")

				repository.confirmInstall(1, "com.example")?.join()

				assertThat(pairedLocation()).isEqualTo(dir.canonicalPath)
				assertThat(announced).containsExactly("com.example")
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `a noted install writes nothing until its session succeeds`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				repository.notePendingInstall(1, dir, "com.example")

				assertThat(pairedLocation()).isNull()
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `a stale note is not used by another session's result or an install Code on the Go did not start`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				repository.notePendingInstall(1, dir, "com.example")

				// Session 2 has no note: an APK opened from the file tree, say, of the same application ID.
				assertThat(repository.confirmInstall(2, "com.example")).isNull()
				assertThat(pairedLocation()).isNull()

				repository.confirmInstall(1, "com.example")?.join()
				assertThat(pairedLocation()).isEqualTo(dir.canonicalPath)
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `two projects with the same application ID each pair with their own session`() =
		runBlocking<Unit> {
			val projectA = newProject()
			val projectB = newProject()
			try {
				repository.notePendingInstall(1, projectA, "com.example")
				repository.notePendingInstall(2, projectB, "com.example")

				repository.confirmInstall(1, "com.example")?.join()
				assertThat(pairedLocation()).isEqualTo(projectA.canonicalPath)

				repository.confirmInstall(2, "com.example")?.join()
				assertThat(pairedLocation()).isEqualTo(projectB.canonicalPath)
			} finally {
				projectA.delete()
				projectB.delete()
			}
		}

	@Test
	fun `an install whose prompt stayed open more than 5 minutes still pairs with its own project`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				val now = AtomicLong(1_000L)
				val repo = clockedRepository(now)
				repo.notePendingInstall(1, dir, "com.example")

				now.addAndGet(6 * 60 * 1000L)
				repo.confirmInstall(1, "com.example")?.join()

				assertThat(pairedLocation()).isEqualTo(dir.canonicalPath)
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `a failed install drops only its own session's note`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				repository.notePendingInstall(1, dir, "com.example")
				repository.notePendingInstall(2, dir, "com.example")

				repository.discardPendingInstall(1)

				assertThat(repository.confirmInstall(1, "com.example")).isNull()
				repository.confirmInstall(2, "com.example")?.join()
				assertThat(pairedLocation()).isEqualTo(dir.canonicalPath)
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `a result naming another package than the noted one pairs nothing`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				repository.notePendingInstall(1, dir, "com.example")

				assertThat(repository.confirmInstall(1, "com.other")).isNull()

				assertThat(pairedLocation("com.example")).isNull()
				assertThat(pairedLocation("com.other")).isNull()
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `a result without a package name pairs nothing`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				repository.notePendingInstall(1, dir, "com.example")

				// The framework names the package in every result it sends, so this one was forged.
				assertThat(repository.confirmInstall(1, installedApplicationId = null)).isNull()

				assertThat(pairedLocation()).isNull()
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `an install from the same project clears the token, port and time the replaced process reported`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				repository.recordInstalled("com.example", dir.canonicalPath)
				repository.storeEndpoint("com.example", endpoint)
				repository.notePendingInstall(1, dir, "com.example")

				// A build without the SQLite debugger never reports again, so nothing would replace the dead port.
				repository.confirmInstall(1, "com.example")?.join()

				assertThat(room.debuggerEndpointDao().getByApplicationId("com.example"))
					.isEqualTo(DebuggerEndpoint("com.example", dir.canonicalPath, null, null, null))
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `a report the new process sends before the install's write is cleared, then stored from its reply to the request`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				repository.recordInstalled("com.example", dir.canonicalPath)
				repository.storeEndpoint("com.example", endpoint)
				val fresh = DebuggerEndpointValidation.Endpoint("new-tok_2", 6666)
				val seenWhenAsked = CopyOnWriteArrayList<Pair<String?, Int?>>()
				lateinit var replying: DebuggerEndpointRepository
				replying =
					DebuggerEndpointRepository(
						room.debuggerEndpointDao(),
						CoroutineScope(Dispatchers.IO),
						clock = { 1234L },
						isInstalled = { true },
						announce = { id ->
							// The running app replies at once, the tightest case: before anything after the request runs.
							runBlocking {
								val row = room.debuggerEndpointDao().getByApplicationId(id)
								seenWhenAsked += row?.token to row?.port
								replying.storeEndpoint(id, fresh)
							}
						},
					)
				replying.notePendingInstall(1, dir, "com.example")
				// The post-install launch does not wait for the write, so the new process can report first.
				assertThat(replying.storeEndpoint("com.example", fresh)).isTrue()

				replying.confirmInstall(1, "com.example")!!.join()

				val row = room.debuggerEndpointDao().getByApplicationId("com.example")!!
				assertThat(row.token to row.port).isEqualTo("new-tok_2" to 6666)
				// Cleared before the request, in the pairing's write: a clear after it could wipe the reply for good.
				assertThat(seenWhenAsked).containsExactly(null to null)
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
	fun `pairing on launch asks the app to report its endpoint again`() =
		runBlocking<Unit> {
			addProject("/p/a")

			repository.recordLaunched("com.example", "/p/a")

			assertThat(announced).containsExactly("com.example")
		}

	@Test
	fun `pairing on install asks the app to report its endpoint again`() =
		runBlocking<Unit> {
			addProject("/p/a")

			repository.recordInstalled("com.example", "/p/a")

			assertThat(announced).containsExactly("com.example")
		}

	@Test
	fun `a pairing that was not written asks nothing`() =
		runBlocking<Unit> {
			repository.recordLaunched("com.example", "/p/missing")
			repository.recordInstalled("com.example", "/p/missing")

			assertThat(announced).isEmpty()
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

		override suspend fun clearEndpoint(applicationId: String): Unit = throw error()

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
				val failing =
					DebuggerEndpointRepository(
						ThrowingDao(error),
						CoroutineScope(Dispatchers.IO),
						isInstalled = { true },
						announce = { announced += it },
					)

				assertThat(failing.recordInstalled("com.example", "/p/a")).isFalse()
				assertThat(failing.recordLaunched("com.example", "/p/a")).isFalse()
				assertThat(failing.storeEndpoint("com.example", endpoint)).isFalse()
			}
			assertThat(announced).isEmpty()
		}

	@Test
	fun `cancellation is not swallowed`() {
		val failing =
			DebuggerEndpointRepository(
				ThrowingDao { kotlinx.coroutines.CancellationException("cancelled") },
				CoroutineScope(Dispatchers.IO),
				isInstalled = { true },
				announce = {},
			)

		val result = runCatching { runBlocking { failing.storeEndpoint("com.example", endpoint) } }

		assertThat(result.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
	}

	@Test
	fun `reported endpoints are found through a non-canonical path and unreported apps are left out`() =
		runBlocking<Unit> {
			val dir =
				java.nio.file.Files
					.createTempDirectory("proj")
					.toFile()
			try {
				addProject(dir.canonicalPath)
				assertThat(repository.recordInstalled("com.example.reported", dir.canonicalPath)).isTrue()
				assertThat(repository.recordInstalled("com.example.silent", dir.canonicalPath)).isTrue()
				assertThat(repository.storeEndpoint("com.example.reported", endpoint)).isTrue()

				val endpoints = repository.reportedEndpoints(File(dir, "../" + dir.name))

				assertThat(endpoints.map { it.applicationId }).containsExactly("com.example.reported")
				val e = endpoints.single()
				assertThat(e.port).isEqualTo(5555)
				assertThat(e.token).isEqualTo("tok-en_1")
				assertThat(e.updatedAtMillis).isEqualTo(1234L)
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `an app that has not reported is dropped without asking whether it is installed, a binder call`() =
		runBlocking<Unit> {
			val dir =
				java.nio.file.Files
					.createTempDirectory("proj")
					.toFile()
			try {
				addProject(dir.canonicalPath)
				val asked = CopyOnWriteArrayList<String>()
				val counting =
					DebuggerEndpointRepository(
						room.debuggerEndpointDao(),
						CoroutineScope(Dispatchers.IO),
						clock = { 1234L },
						isInstalled = {
							asked += it
							true
						},
						announce = {},
					)
				assertThat(counting.recordInstalled("com.example.reported", dir.canonicalPath)).isTrue()
				assertThat(counting.recordInstalled("com.example.silent", dir.canonicalPath)).isTrue()
				assertThat(counting.storeEndpoint("com.example.reported", endpoint)).isTrue()

				assertThat(counting.reportedEndpoints(dir).map { it.applicationId }).containsExactly("com.example.reported")
				assertThat(asked).containsExactly("com.example.reported")
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `an app that is no longer installed is left out and installed ones are returned`() =
		runBlocking<Unit> {
			val dir =
				java.nio.file.Files
					.createTempDirectory("proj")
					.toFile()
			try {
				addProject(dir.canonicalPath)
				val installed = setOf("com.example.alive")
				val pruning =
					DebuggerEndpointRepository(
						room.debuggerEndpointDao(),
						CoroutineScope(Dispatchers.IO),
						clock = { 1234L },
						isInstalled = { it in installed },
						announce = {},
					)
				for (id in listOf("com.example.alive", "com.example.gone")) {
					assertThat(pruning.recordInstalled(id, dir.canonicalPath)).isTrue()
					assertThat(pruning.storeEndpoint(id, endpoint)).isTrue()
				}

				val endpoints = pruning.reportedEndpoints(dir)

				assertThat(endpoints.map { it.applicationId }).containsExactly("com.example.alive")
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `an app reinstalled from its project is left out until its new process reports`() =
		runBlocking<Unit> {
			val dir = newProject()
			try {
				assertThat(repository.recordInstalled("com.example", dir.canonicalPath)).isTrue()
				assertThat(repository.storeEndpoint("com.example", endpoint)).isTrue()
				repository.notePendingInstall(1, dir, "com.example")

				// The app is still installed, so only the clear keeps a build without the SQLite debugger out.
				repository.confirmInstall(1, "com.example")!!.join()
				assertThat(repository.reportedEndpoints(dir)).isEmpty()

				assertThat(repository.storeEndpoint("com.example", DebuggerEndpointValidation.Endpoint("new-tok_2", 6666))).isTrue()
				assertThat(repository.reportedEndpoints(dir).map { it.token to it.port }).containsExactly("new-tok_2" to 6666)
			} finally {
				dir.delete()
			}
		}

	@Test
	fun `each project gets only its own apps, also through a symlinked path, and an app moved away leaves the old one`() =
		runBlocking<Unit> {
			val a =
				java.nio.file.Files
					.createTempDirectory("projA")
					.toFile()
			val b =
				java.nio.file.Files
					.createTempDirectory("projB")
					.toFile()
			val link = File(a.parentFile, a.name + "-link")
			java.nio.file.Files
				.createSymbolicLink(link.toPath(), a.toPath())
			try {
				addProject(a.canonicalPath)
				addProject(b.canonicalPath)
				assertThat(repository.recordInstalled("com.example.a", a.canonicalPath)).isTrue()
				assertThat(repository.recordInstalled("com.example.b", b.canonicalPath)).isTrue()
				assertThat(repository.storeEndpoint("com.example.a", DebuggerEndpointValidation.Endpoint("tok-a", 1111))).isTrue()
				assertThat(repository.storeEndpoint("com.example.b", DebuggerEndpointValidation.Endpoint("tok-b", 2222))).isTrue()

				assertThat(repository.reportedEndpoints(a).map { it.applicationId }).containsExactly("com.example.a")
				assertThat(repository.reportedEndpoints(b).map { it.applicationId }).containsExactly("com.example.b")
				assertThat(repository.reportedEndpoints(link).map { it.token }).containsExactly("tok-a")

				// The app is now built by project b: a no longer lists it, and its token was cleared.
				assertThat(repository.recordInstalled("com.example.a", b.canonicalPath)).isTrue()
				assertThat(repository.reportedEndpoints(a)).isEmpty()
				assertThat(repository.reportedEndpoints(b).map { it.applicationId }).containsExactly("com.example.b")
			} finally {
				link.delete()
				a.delete()
				b.delete()
			}
		}

	@Test
	fun `the package manager check tells installed from missing apps`() {
		val app = ApplicationProvider.getApplicationContext<android.app.Application>()
		val packageManager = app.packageManager

		assertThat(DebuggerEndpointRepository.isPackageInstalled(packageManager, app.packageName)).isTrue()
		assertThat(DebuggerEndpointRepository.isPackageInstalled(packageManager, "com.example.not.installed")).isFalse()
	}
}
