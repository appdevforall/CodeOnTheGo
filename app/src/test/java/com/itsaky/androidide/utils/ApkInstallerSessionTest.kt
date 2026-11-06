package com.itsaky.androidide.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ApkInstallerSessionTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Before
	@After
	fun resetInFlightSessions() {
		ApkInstaller.clearInFlightUserSessionsForTesting()
	}

	private fun sessionInfo(id: Int): PackageInstaller.SessionInfo =
		mockk {
			every { sessionId } returns id
		}

	@Test
	fun `abandonMySessions abandons all 50 sessions and tolerates already-finalized ones`() {
		val active = (1..50).toMutableSet()
		val installer = mockk<PackageInstaller>()
		every { installer.mySessions } answers { active.map(::sessionInfo) }
		every { installer.abandonSession(any()) } answers {
			val id = firstArg<Int>()
			if (id == 25) {
				throw SecurityException("Session 25 already finalized")
			}
			active.remove(id)
		}

		val abandoned = ApkInstaller.abandonMySessions(installer)

		assertThat(abandoned).isEqualTo(49)
		assertThat(active).containsExactly(25)
	}

	@Test
	fun `installUsingSession does not abandon other in-flight sessions and abandonMySessions preserves pending user installs`() =
		runTest {
			val apk = tempFolder.newFile("app-debug.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
			val activeSessions = (1..49).toMutableSet()
			var nextId = 100

			val session = mockk<PackageInstaller.Session>(relaxed = true)
			every { session.openWrite(any(), any(), any()) } returns ByteArrayOutputStream()
			justRun { session.fsync(any()) }
			justRun { session.commit(any()) }
			justRun { session.close() }

			val installer = mockk<PackageInstaller>()
			every { installer.mySessions } answers { activeSessions.map(::sessionInfo) }
			every { installer.abandonSession(any()) } answers {
				activeSessions.remove(firstArg<Int>())
			}
			every { installer.createSession(any()) } answers {
				if (activeSessions.size >= 50) {
					throw IllegalStateException("Too many active sessions for UID 10600")
				}
				val id = nextId++
				activeSessions += id
				id
			}
			every { installer.openSession(any()) } returns session

			val baseContext = ApplicationProvider.getApplicationContext<Context>()
			val pm = spyk(baseContext.packageManager)
			every { pm.packageInstaller } returns installer
			val context = spyk(baseContext)
			every { context.packageManager } returns pm

			// 1. Start a Run-button / InstallFileAction install (session 100); it is now waiting for confirmation.
			val runButtonResult = ApkInstaller.installUsingSession(context, apk, Intent())
			assertThat(runButtonResult).isEqualTo(ApkInstaller.SessionLaunchResult.Started(100))
			assertThat(activeSessions).contains(100)

			// 2. Quick Build sweeps stale sessions before provisioning: all 49 stale sessions are
			//    abandoned, but the pending Run-button session (100) is preserved.
			val swept = ApkInstaller.abandonMySessions(installer)
			assertThat(swept).isEqualTo(49)
			assertThat(activeSessions).containsExactly(100)

			// 3. Quick Build starts its proxy install (session 101): session 100 is still not abandoned.
			val quickBuildIntent =
				Intent().putExtra(ApkInstaller.EXTRA_SUPPRESS_POST_INSTALL_LAUNCH, true)
			val quickBuildResult = ApkInstaller.installUsingSession(context, apk, quickBuildIntent)
			assertThat(quickBuildResult).isEqualTo(ApkInstaller.SessionLaunchResult.Started(101))
			assertThat(activeSessions).containsExactly(100, 101)

			// 4. Reverse direction: another Run-button / InstallFileAction install (session 102)
			//    does not abandon the pending Quick Build session (101).
			val secondUserInstall = ApkInstaller.installUsingSession(context, apk, Intent())
			assertThat(secondUserInstall).isEqualTo(ApkInstaller.SessionLaunchResult.Started(102))
			assertThat(activeSessions).containsExactly(100, 101, 102)

			// 5. Once session 100 delivers a terminal broadcast, it is no longer protected as in-flight.
			ApkInstaller.onInstallStatusBroadcast(
				Intent()
					.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
					.putExtra(PackageInstaller.EXTRA_SESSION_ID, 100),
			)
			ApkInstaller.abandonMySessions(installer)
			assertThat(activeSessions).containsExactly(102)
		}

	@Test
	fun `installUsingSession reports TooManySessions distinctly from Failed when cap remains full`() =
		runTest {
			val apk = tempFolder.newFile("app-debug.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

			val installer = mockk<PackageInstaller>()
			every { installer.mySessions } returns emptyList()
			every { installer.createSession(any()) } throws
				IllegalStateException("Too many active sessions for UID 10600")

			val baseContext = ApplicationProvider.getApplicationContext<Context>()
			val pm = spyk(baseContext.packageManager)
			every { pm.packageInstaller } returns installer
			val context = spyk(baseContext)
			every { context.packageManager } returns pm

			val capResult = ApkInstaller.installUsingSession(context, apk, Intent())

			every { installer.createSession(any()) } throws IllegalStateException("Some other error")
			val genericResult = ApkInstaller.installUsingSession(context, apk, Intent())

			assertThat(capResult).isEqualTo(ApkInstaller.SessionLaunchResult.TooManySessions)
			assertThat(genericResult).isEqualTo(ApkInstaller.SessionLaunchResult.Failed)
		}
}
