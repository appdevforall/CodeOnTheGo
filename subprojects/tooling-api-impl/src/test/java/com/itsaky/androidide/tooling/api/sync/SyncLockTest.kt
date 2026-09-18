/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.tooling.api.sync

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.SharedEnvironment
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * Tests that the sync lock serialises threads within this process.
 *
 * @author Akash Yadav
 */
@RunWith(JUnit4::class)
class SyncLockTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun `a second thread cannot take the sync lock while it is held`() {
		val projectDir = temporaryFolder.newFolder("project")
		val channel = ProjectSyncHelper.tryAcquireSyncLock(projectDir, ACQUIRE_TIMEOUT_MS)
		assertThat(channel).isNotNull()

		try {
			assertThat(acquireOnAnotherThread(projectDir)).isNull()
		} finally {
			ProjectSyncHelper.releaseSyncLock(channel)
		}
	}

	@Test
	fun `the sync lock is available again once it is released`() {
		val projectDir = temporaryFolder.newFolder("project")
		ProjectSyncHelper.releaseSyncLock(
			ProjectSyncHelper.tryAcquireSyncLock(projectDir, ACQUIRE_TIMEOUT_MS),
		)

		val reacquired = acquireOnAnotherThread(projectDir)
		assertThat(reacquired).isNotNull()
		ProjectSyncHelper.releaseSyncLock(reacquired)
	}

	@Test
	fun `a failed channel open does not leak the in-process permit`() {
		val projectDir = temporaryFolder.newFolder("project")
		ProjectSyncHelper.releaseSyncLock(ProjectSyncHelper.tryAcquireSyncLock(projectDir, ACQUIRE_TIMEOUT_MS))

		val lockFile = File(projectDir, SharedEnvironment.PROJECT_SYNC_CACHE_LOCK_FILE)
		lockFile.setWritable(false)
		try {
			assumeTrue(runCatching { FileOutputStream(lockFile).use { } }.isFailure)
			assertThat(ProjectSyncHelper.tryAcquireSyncLock(projectDir, CONTENDED_TIMEOUT_MS)).isNull()
		} finally {
			lockFile.setWritable(true)
		}

		// A permit held by the failed attempt would make the lock unavailable for this JVM's life.
		val reacquired = ProjectSyncHelper.tryAcquireSyncLock(projectDir, ACQUIRE_TIMEOUT_MS)
		assertThat(reacquired).isNotNull()
		ProjectSyncHelper.releaseSyncLock(reacquired)
	}

	/**
	 * Two spellings of one project directory contend for a single lock.
	 *
	 * This covers the aliasing, not the hazard behind it: a second mutex would open a second channel
	 * whose close drops the first holder's fcntl lock, and only another process can observe that.
	 */
	@Test
	fun `an aliased project path contends for the same lock`() {
		val projectDir = temporaryFolder.newFolder("project")
		val alias = File(temporaryFolder.root, "alias")
		assumeTrue(runCatching { Files.createSymbolicLink(alias.toPath(), projectDir.toPath()) }.isSuccess)

		val channel = ProjectSyncHelper.tryAcquireSyncLock(projectDir, ACQUIRE_TIMEOUT_MS)
		assertThat(channel).isNotNull()

		try {
			assertThat(acquireOnAnotherThread(alias)).isNull()
		} finally {
			ProjectSyncHelper.releaseSyncLock(channel)
		}

		val viaAlias = acquireOnAnotherThread(alias)
		assertThat(viaAlias).isNotNull()
		ProjectSyncHelper.releaseSyncLock(viaAlias)
	}

	private fun acquireOnAnotherThread(projectDir: File): FileChannel? {
		val executor = Executors.newSingleThreadExecutor()
		try {
			return executor
				.submit<FileChannel?> {
					ProjectSyncHelper.tryAcquireSyncLock(projectDir, CONTENDED_TIMEOUT_MS)
				}.get()
		} finally {
			executor.shutdownNow()
		}
	}

	private companion object {
		const val ACQUIRE_TIMEOUT_MS = 2_000L
		const val CONTENDED_TIMEOUT_MS = 200L
	}
}
