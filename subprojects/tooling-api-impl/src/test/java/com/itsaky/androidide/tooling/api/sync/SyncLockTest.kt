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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.channels.FileChannel
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

	private fun acquireOnAnotherThread(projectDir: java.io.File): FileChannel? {
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
