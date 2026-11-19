package com.itsaky.androidide.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.events.InstallationEvent.InstallationResultEvent
import com.itsaky.androidide.repositories.DebuggerEndpointRepository
import com.itsaky.androidide.roomData.recentproject.DebuggerEndpointDao
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.sqlitedebugger.DebuggerEndpointValidation
import com.itsaky.androidide.utils.ApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SQLite debugger pairing of an install (ADFA-7137) is written by the app-wide receiver, not by the editor,
 * which sees the result event only while it is started, and the event that launches the app does not wait for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class InstallationResultReceiverTest {
	private lateinit var room: RecentProjectRoomDatabase
	private lateinit var project: File
	private val context: Context get() = ApplicationProvider.getApplicationContext()
	private var recorder: Recorder? = null

	/** Stands in for the editor's subscriber, which launches the app. */
	class Recorder(
		private val onEvent: (InstallationResultEvent) -> Unit,
	) {
		@Subscribe
		fun on(event: InstallationResultEvent) = onEvent(event)
	}

	@Before
	fun setUp() {
		room =
			Room
				.inMemoryDatabaseBuilder(context, RecentProjectRoomDatabase::class.java)
				.allowMainThreadQueries()
				.build()
		project =
			java.nio.file.Files
				.createTempDirectory("proj")
				.toFile()
		runBlocking { room.recentProjectDao().insert(RecentProject(name = "a", createdAt = "1", location = project.canonicalPath)) }
	}

	@After
	fun tearDown() {
		recorder?.let { EventBus.getDefault().unregister(it) }
		if (GlobalContext.getOrNull() != null) stopKoin()
		room.close()
		project.delete()
	}

	private fun repository(
		dao: DebuggerEndpointDao = room.debuggerEndpointDao(),
		scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
	): DebuggerEndpointRepository {
		val repository = DebuggerEndpointRepository(dao, scope, isInstalled = { true }, announce = {})
		if (GlobalContext.getOrNull() != null) stopKoin()
		startKoin { modules(module { single { repository } }) }
		return repository
	}

	private fun record(onEvent: (InstallationResultEvent) -> Unit) {
		recorder = Recorder(onEvent).also { EventBus.getDefault().register(it) }
	}

	private fun deliver(
		sessionId: Int,
		status: Int,
		suppressLaunch: Boolean = false,
	) {
		val intent =
			Intent(InstallationResultReceiver.ACTION_INSTALL_STATUS)
				.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
				.putExtra(PackageInstaller.EXTRA_STATUS, status)
				.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, "com.example")
		if (suppressLaunch) intent.putExtra(ApkInstaller.EXTRA_SUPPRESS_POST_INSTALL_LAUNCH, true)
		InstallationResultReceiver().onReceive(context, intent)
	}

	/** The project the app is paired with, once the write on the application scope lands; null after 2 s. */
	private fun awaitPairedLocation(): String? {
		repeat(100) {
			runBlocking { room.debuggerEndpointDao().getByApplicationId("com.example") }?.let { return it.projectLocation }
			Thread.sleep(20)
		}
		return null
	}

	@Test
	fun `a success that arrives while the editor is stopped still pairs`() {
		val repository = repository()
		repository.notePendingInstall(1, project, "com.example")

		// No subscriber: the stopped editor has unregistered from EventBus.
		deliver(1, PackageInstaller.STATUS_SUCCESS)

		assertThat(awaitPairedLocation()).isEqualTo(project.canonicalPath)
	}

	@Test
	fun `the suppress-launch flag does not stop a noted success from pairing and using up the note`() {
		// No install sets the flag and has a note today: Quick Build, the one that sets it, notes nothing.
		val repository = repository()
		repository.notePendingInstall(1, project, "com.example")

		deliver(1, PackageInstaller.STATUS_SUCCESS, suppressLaunch = true)

		assertThat(awaitPairedLocation()).isEqualTo(project.canonicalPath)
		assertThat(repository.confirmInstall(1, "com.example")).isNull()
	}

	@Test
	fun `a failure drops that session's note, and the install prompt keeps it`() {
		val repository = repository()
		repository.notePendingInstall(1, project, "com.example")
		repository.notePendingInstall(2, project, "com.example")

		deliver(1, PackageInstaller.STATUS_FAILURE_ABORTED)
		deliver(2, PackageInstaller.STATUS_PENDING_USER_ACTION)

		assertThat(repository.confirmInstall(1, "com.example")).isNull()
		assertThat(repository.confirmInstall(2, "com.example")).isNotNull()
	}

	/** Parent of the writes of a repository built with [writesScope], so a test can wait for all of them. */
	private val writes = SupervisorJob()
	private val writesScope = CoroutineScope(Dispatchers.IO + writes)

	private fun awaitWrites() = runBlocking { writes.children.toList().joinAll() }

	/** Pairs com.example with the project and stores the endpoint its running build reported. */
	private fun pairWithReportedEndpoint(repository: DebuggerEndpointRepository) =
		runBlocking {
			assertThat(repository.recordInstalled("com.example", project.canonicalPath)).isTrue()
			assertThat(repository.storeEndpoint("com.example", DebuggerEndpointValidation.Endpoint("tok-en_1", 5555))).isTrue()
		}

	private fun storedEndpoint(): Pair<String?, Int?> =
		runBlocking { room.debuggerEndpointDao().getByApplicationId("com.example")!! }.let { it.token to it.port }

	@Test
	fun `a success clears the endpoint the replaced process reported`() {
		val repository = repository(scope = writesScope)
		pairWithReportedEndpoint(repository)
		repository.notePendingInstall(1, project, "com.example")

		deliver(1, PackageInstaller.STATUS_SUCCESS)
		awaitWrites()

		assertThat(storedEndpoint()).isEqualTo(null to null)
	}

	@Test
	fun `a failure leaves the stored endpoint as it was`() {
		val repository = repository(scope = writesScope)
		pairWithReportedEndpoint(repository)
		val failures =
			listOf(
				PackageInstaller.STATUS_FAILURE,
				PackageInstaller.STATUS_FAILURE_ABORTED,
				PackageInstaller.STATUS_FAILURE_BLOCKED,
				PackageInstaller.STATUS_FAILURE_CONFLICT,
				PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
				PackageInstaller.STATUS_FAILURE_INVALID,
				PackageInstaller.STATUS_FAILURE_STORAGE,
			)
		failures.indices.forEach { repository.notePendingInstall(it + 1, project, "com.example") }

		// The old APK, and the process that reported, are still there.
		failures.forEachIndexed { i, status -> deliver(i + 1, status) }
		awaitWrites()

		assertThat(storedEndpoint()).isEqualTo("tok-en_1" to 5555)
	}

	@Test
	fun `the event that launches the app is posted before a slow pairing write finishes`() {
		val release = CountDownLatch(1)
		val written = AtomicBoolean(false)
		val roomDao = room.debuggerEndpointDao()
		val slowDao =
			object : DebuggerEndpointDao by roomDao {
				override suspend fun upsertPairing(
					applicationId: String,
					projectLocation: String,
				) {
					// Gives up after 2 s, so a receiver that waited for the write would post late, not hang the test.
					withContext(Dispatchers.IO) { release.await(2, TimeUnit.SECONDS) }
					roomDao.upsertPairing(applicationId, projectLocation)
					written.set(true)
				}
			}
		val repository = repository(dao = slowDao)
		repository.notePendingInstall(1, project, "com.example")
		var writtenWhenPosted: Boolean? = null
		record { writtenWhenPosted = written.get() }

		deliver(1, PackageInstaller.STATUS_SUCCESS)

		assertThat(writtenWhenPosted).isFalse()
		release.countDown()
		assertThat(awaitPairedLocation()).isEqualTo(project.canonicalPath)
	}

	@Test
	fun `the event that launches the app is posted when the pairing scope is cancelled`() {
		val repository = repository(scope = CoroutineScope(Job().apply { cancel() }))
		repository.notePendingInstall(1, project, "com.example")
		var posted = false
		record { posted = true }

		deliver(1, PackageInstaller.STATUS_SUCCESS)

		assertThat(posted).isTrue()
	}
}
