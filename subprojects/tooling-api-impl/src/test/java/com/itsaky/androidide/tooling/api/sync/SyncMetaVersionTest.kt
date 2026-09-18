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
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Tests that sync files written by an incompatible schema version are discarded and resynced.
 *
 * @author Akash Yadav
 */
@RunWith(JUnit4::class)
class SyncMetaVersionTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun `sync is not needed when the stored meta version is current`() {
		val projectDir = seedProject()
		seedSyncFiles(projectDir, ProjectSyncHelper.SYNC_META_VERSION)

		assertThat(runBlocking { ProjectSyncHelper.checkSyncNeeded(projectDir) }).isFalse()
		assertSyncFilesExist(projectDir)
	}

	@Test
	fun `sync is needed and the stale files are discarded when the stored meta version differs`() {
		val projectDir = seedProject()
		seedSyncFiles(projectDir, metaVersion = "0")

		assertThat(runBlocking { ProjectSyncHelper.checkSyncNeeded(projectDir) }).isTrue()
		assertSyncFilesDiscarded(projectDir)
	}

	@Test
	fun `sync is needed and the stale files are discarded when the stored meta is unreadable`() {
		val projectDir = seedProject()
		seedSyncFiles(projectDir, ProjectSyncHelper.SYNC_META_VERSION)

		// field number 0 is not a valid protobuf tag
		ProjectSyncHelper.syncMetaFileForProject(projectDir).writeBytes(byteArrayOf(0, 1, 2, 3))

		assertThat(runBlocking { ProjectSyncHelper.checkSyncNeeded(projectDir) }).isTrue()
		assertSyncFilesDiscarded(projectDir)
	}

	@Test
	fun `a sync is still requested when the stale sync files cannot be discarded`() {
		val projectDir = seedProject()
		seedSyncFiles(projectDir, metaVersion = "0")

		// Blocks the lock file from being created, so the discard fails before it deletes anything.
		val syncDir = ProjectSyncHelper.syncMetaFileForProject(projectDir).parentFile
		syncDir.setWritable(false)

		try {
			/*
			 * Probe rather than trust the chmod's return value: running as root it succeeds and
			 * writes still go through, which would fail this test for the wrong reason. Inside the
			 * try, so a skipped run still restores the directory.
			 */
			assumeTrue(runCatching { File(syncDir, "probe").createNewFile() }.getOrDefault(false).not())

			assertThat(runBlocking { ProjectSyncHelper.checkSyncNeeded(projectDir) }).isTrue()
			assertSyncFilesExist(projectDir)
		} finally {
			syncDir.setWritable(true)
		}
	}

	@Test
	fun `the stored meta version is current when a sync wrote it`() {
		val projectDir = seedProject()
		seedSyncFiles(projectDir, ProjectSyncHelper.SYNC_META_VERSION)

		assertThat(
			ProjectSyncHelper.isSyncMetaVersionCurrent(
				ProjectSyncHelper.syncMetaFileForProject(projectDir),
			),
		).isTrue()
	}

	@Test
	fun `the stored meta version is not current when an older schema wrote it`() {
		val projectDir = seedProject()
		seedSyncFiles(projectDir, metaVersion = "0")

		assertThat(
			ProjectSyncHelper.isSyncMetaVersionCurrent(
				ProjectSyncHelper.syncMetaFileForProject(projectDir),
			),
		).isFalse()
	}

	@Test
	fun `the stored meta version is not current when the meta cannot be read`() {
		val projectDir = seedProject()

		assertThat(
			ProjectSyncHelper.isSyncMetaVersionCurrent(
				ProjectSyncHelper.syncMetaFileForProject(projectDir),
			),
		).isFalse()
	}

	private fun seedProject(): File {
		val projectDir = temporaryFolder.newFolder("project")
		projectDir.resolve("build.gradle").writeText("plugins { id 'java' }")
		return projectDir
	}

	/**
	 * Write a sync metadata file carrying [metaVersion], plus a project model cache file beside it,
	 * the way a completed sync leaves them.
	 */
	private fun seedSyncFiles(
		projectDir: File,
		metaVersion: String,
	) {
		val metaFile = ProjectSyncHelper.syncMetaFileForProject(projectDir)
		val cacheFile = ProjectSyncHelper.cacheFileForProject(projectDir)
		metaFile.parentFile.mkdirs()

		cacheFile.writeBytes(byteArrayOf())

		val meta =
			runBlocking {
				ProjectSyncHelper.createSyncMeta(projectDir, includeChecksum = true)
			}.toBuilder()
				.setMetaVersion(metaVersion)
				.build()

		metaFile.outputStream().buffered().use { out ->
			meta.writeTo(out)
			out.flush()
		}
	}

	private fun assertSyncFilesExist(projectDir: File) {
		assertThat(ProjectSyncHelper.syncMetaFileForProject(projectDir).exists()).isTrue()
		assertThat(ProjectSyncHelper.cacheFileForProject(projectDir).exists()).isTrue()
	}

	private fun assertSyncFilesDiscarded(projectDir: File) {
		assertThat(ProjectSyncHelper.syncMetaFileForProject(projectDir).exists()).isFalse()
		assertThat(ProjectSyncHelper.cacheFileForProject(projectDir).exists()).isFalse()
	}
}
