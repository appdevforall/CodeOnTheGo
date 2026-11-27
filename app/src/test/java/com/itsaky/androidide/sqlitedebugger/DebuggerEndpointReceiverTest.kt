package com.itsaky.androidide.sqlitedebugger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.di.APPLICATION_SCOPE
import com.itsaky.androidide.repositories.DebuggerEndpointRepository
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Delivers broadcasts through the framework, so goAsync() returns a real PendingResult. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DebuggerEndpointReceiverTest {
	private lateinit var room: RecentProjectRoomDatabase
	private val context: Context get() = ApplicationProvider.getApplicationContext()

	@Before
	fun setUp() {
		room =
			Room
				.inMemoryDatabaseBuilder(context, RecentProjectRoomDatabase::class.java)
				.allowMainThreadQueries()
				.build()
		val scope = CoroutineScope(Dispatchers.IO)
		val repository =
			DebuggerEndpointRepository(room.debuggerEndpointDao(), scope, clock = { 99L }, isInstalled = { true }, announce = {})
		if (GlobalContext.getOrNull() != null) stopKoin()
		startKoin {
			modules(
				module {
					single { repository }
					single<CoroutineScope>(named(APPLICATION_SCOPE)) { scope }
				},
			)
		}
		runBlocking {
			room.recentProjectDao().insert(RecentProject(name = "a", createdAt = "1", location = "/p/a"))
			repository.recordInstalled(context.packageName, "/p/a")
		}
	}

	@After
	fun tearDown() {
		room.close()
		stopKoin()
	}

	private fun report(
		withSender: Boolean = true,
		claimedApplicationId: String? = null,
		token: Any? = "tok-en_1",
		port: Any? = 5555,
	) {
		val intent =
			Intent(DebuggerEndpointValidation.ACTION)
				.setPackage(context.packageName)
		if (withSender) {
			// creatorPackage of a PendingIntent built here is this test app's package.
			intent.putExtra(
				DebuggerEndpointValidation.EXTRA_SENDER,
				PendingIntent.getBroadcast(context, 0, Intent("identity").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE),
			)
		}
		if (claimedApplicationId != null) intent.putExtra("application_id", claimedApplicationId)
		when (token) {
			is String -> intent.putExtra(DebuggerEndpointValidation.EXTRA_TOKEN, token)
			is Int -> intent.putExtra(DebuggerEndpointValidation.EXTRA_TOKEN, token)
		}
		when (port) {
			is Int -> intent.putExtra(DebuggerEndpointValidation.EXTRA_PORT, port)
			is String -> intent.putExtra(DebuggerEndpointValidation.EXTRA_PORT, port)
		}
		context.sendBroadcast(intent)
		shadowOf(Looper.getMainLooper()).idle()
	}

	private fun row(id: String): com.itsaky.androidide.roomData.recentproject.DebuggerEndpoint? {
		// The receiver stores on an IO thread; wait for it.
		repeat(60) {
			val r = runBlocking { room.debuggerEndpointDao().getByApplicationId(id) }
			if (r?.token != null) return r
			Thread.sleep(50)
		}
		return runBlocking { room.debuggerEndpointDao().getByApplicationId(id) }
	}

	@Test
	fun `a valid report from a paired app is stored`() {
		report()

		val row = row(context.packageName)!!
		assertThat(row.token).isEqualTo("tok-en_1")
		assertThat(row.port).isEqualTo(5555)
		assertThat(row.updatedAt).isEqualTo(99L)
	}

	@Test
	fun `a report without the sender PendingIntent stores nothing even when it claims a paired id`() {
		report(withSender = false, claimedApplicationId = context.packageName)
		Thread.sleep(500)

		val row = runBlocking { room.debuggerEndpointDao().getByApplicationId(context.packageName) }!!
		assertThat(row.token).isNull()
		assertThat(row.port).isNull()
	}

	@Test
	fun `a claimed application_id extra is ignored when the sender is another paired app`() {
		runBlocking { room.debuggerEndpointDao().upsertPairing("com.victim", "/p/a") }

		report(claimedApplicationId = "com.victim")

		assertThat(row(context.packageName)!!.token).isEqualTo("tok-en_1")
		assertThat(runBlocking { room.debuggerEndpointDao().getByApplicationId("com.victim") }!!.token).isNull()
	}

	@Test
	fun `a report from an unpaired app is not stored`() {
		runBlocking { room.recentProjectDao().deleteByLocation("/p/a") }

		report()
		Thread.sleep(500)

		assertThat(runBlocking { room.debuggerEndpointDao().getByApplicationId(context.packageName) }).isNull()
	}

	@Test
	fun `malformed extras are ignored without throwing`() {
		report(token = 7)
		report(port = "not-a-port")
		report(token = "bad token!")
		report(port = 70000)
		Thread.sleep(500)

		assertThat(runBlocking { room.debuggerEndpointDao().getByApplicationId(context.packageName) }!!.token).isNull()
	}
}
