package com.itsaky.androidide.git.core

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class GitRepositoryManagerTest {
	private lateinit var tempDir: File

	@Before
	fun setup() {
		mockkStatic(android.util.Log::class)
		every { android.util.Log.e(any(), any()) } returns 0
		tempDir = Files.createTempDirectory("git-manager-test").toFile()
	}

	@After
	fun tearDown() {
		unmockkStatic(android.util.Log::class)
		tempDir.deleteRecursively()
	}

	@Test
	fun `isRepository returns false for empty directory`(): Unit =
		runTest {
			assertFalse(GitRepositoryManager.isRepository(tempDir))
		}

	@Test
	fun `initRepository creates a valid git repository`(): Unit =
		runTest {
			val repo = GitRepositoryManager.initRepository(tempDir)

			assertNotNull(repo)
			assertTrue(GitRepositoryManager.isRepository(tempDir))
			assertEquals(tempDir.canonicalPath, repo.rootDir.canonicalPath)

			repo.close()
		}

	@Test
	fun `openRepository returns null for empty directory`() =
		runTest {
			val repo = GitRepositoryManager.openRepository(tempDir)
			assertEquals(null, repo)
		}

	@Test
	fun `openRepository returns valid repository after initialization`() =
		runTest {
			val initializedRepo = GitRepositoryManager.initRepository(tempDir)
			initializedRepo.close()

			val openedRepo = GitRepositoryManager.openRepository(tempDir)
			assertNotNull(openedRepo)
			assertEquals(tempDir.canonicalPath, openedRepo?.rootDir?.canonicalPath)

			openedRepo?.close()
		}
}
