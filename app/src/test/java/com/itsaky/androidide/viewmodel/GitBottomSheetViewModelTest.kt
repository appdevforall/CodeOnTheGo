package com.itsaky.androidide.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.git.core.GitRepository
import com.itsaky.androidide.git.core.models.GitBranch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GitBottomSheetViewModelTest {
	@get:Rule
	val instantExecutorRule = InstantTaskExecutorRule()

	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	private val credentialsManager = mockk<GitCredentialsManager>(relaxed = true)
	private val repository = mockk<GitRepository>(relaxed = true)
	private lateinit var viewModel: GitBottomSheetViewModel

	@Before
	fun setup() {
		viewModel =
			GitBottomSheetViewModel(
				credentialsManager = credentialsManager,
				isNetworkConnected = { true },
				repository = repository,
			)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `fetchBranches updates branches state`() =
		runTest {
			val mockBranches =
				listOf(
					GitBranch(name = "main", fullName = "refs/heads/main", isCurrent = true, isRemote = false),
					GitBranch(name = "feature", fullName = "refs/heads/feature", isCurrent = false, isRemote = false),
				)
			coEvery { repository.getBranches() } returns mockBranches

			viewModel.fetchBranches()
			advanceUntilIdle()

			assertEquals(GitBottomSheetViewModel.BranchesUiState.Success(mockBranches), viewModel.branches.value)
		}

	@Test
	fun `checkoutBranch success updates checkoutState to Success and then resets to Idle`() =
		runTest {
			coEvery { repository.checkout("feature", false, null) } returns Unit
			coEvery { repository.getStatus() } returns mockk(relaxed = true)

			var successCalled = false
			viewModel.checkoutBranch("feature", onSuccess = { successCalled = true })
			testScheduler.advanceTimeBy(100)

			val state = viewModel.checkoutState.value
			assertTrue(state is GitBottomSheetViewModel.CheckoutUiState.Success)
			assertEquals("feature", (state as GitBottomSheetViewModel.CheckoutUiState.Success).branchName)
			assertTrue(successCalled)
			coVerify { repository.checkout("feature", false, null) }

			// Advance past 3000ms delay to verify state resets to Idle
			testScheduler.advanceTimeBy(3000)
			assertTrue(viewModel.checkoutState.value is GitBottomSheetViewModel.CheckoutUiState.Idle)
		}

	@Test
	fun `checkoutBranch conflict updates checkoutState to Conflicts and then resets to Idle`() =
		runTest {
			val conflictPaths = listOf("file1.txt", "file2.txt")
			val exception = mockk<CheckoutConflictException>(relaxed = true)
			every { exception.conflictingPaths } returns conflictPaths
			every { exception.getConflictingPaths() } returns conflictPaths
			coEvery { repository.checkout("feature", false, null) } throws exception

			viewModel.checkoutBranch("feature")
			testScheduler.advanceTimeBy(100)

			val state = viewModel.checkoutState.value
			assertTrue(state is GitBottomSheetViewModel.CheckoutUiState.Conflicts)
			assertEquals(conflictPaths, (state as GitBottomSheetViewModel.CheckoutUiState.Conflicts).conflictingPaths)

			// Advance past 3000ms delay to verify state resets to Idle
			testScheduler.advanceTimeBy(3000)
			assertTrue(viewModel.checkoutState.value is GitBottomSheetViewModel.CheckoutUiState.Idle)
		}

	@Test
	fun `mergeBranch success updates mergeState to Success`() =
		runTest {
			val mergeResult = mockk<org.eclipse.jgit.api.MergeResult>(relaxed = true)
			every { mergeResult.mergeStatus } returns MergeStatus.FAST_FORWARD
			coEvery { repository.merge("feature-login") } returns mergeResult
			coEvery { repository.getStatus() } returns mockk(relaxed = true)

			viewModel.mergeBranch("feature-login")
			testScheduler.advanceTimeBy(100)

			val state = viewModel.mergeState.value
			assertTrue(state is GitBottomSheetViewModel.MergeUiState.Success)
			assertEquals("feature-login", (state as GitBottomSheetViewModel.MergeUiState.Success).targetBranch)
			coVerify { repository.merge("feature-login") }
		}

	@Test
	fun `mergeBranch already up to date updates mergeState to AlreadyUpToDate`() =
		runTest {
			val mergeResult = mockk<org.eclipse.jgit.api.MergeResult>(relaxed = true)
			every { mergeResult.mergeStatus } returns MergeStatus.ALREADY_UP_TO_DATE
			coEvery { repository.merge("main") } returns mergeResult

			viewModel.mergeBranch("main")
			testScheduler.advanceTimeBy(100)

			val state = viewModel.mergeState.value
			assertTrue(state is GitBottomSheetViewModel.MergeUiState.AlreadyUpToDate)
			assertEquals("main", (state as GitBottomSheetViewModel.MergeUiState.AlreadyUpToDate).targetBranch)
		}

	@Test
	fun `mergeBranch conflict updates mergeState to Conflicts`() =
		runTest {
			val mergeResult = mockk<org.eclipse.jgit.api.MergeResult>(relaxed = true)
			every { mergeResult.mergeStatus } returns MergeStatus.CONFLICTING
			coEvery { repository.merge("feature-conflict") } returns mergeResult
			val mockStatus = mockk<com.itsaky.androidide.git.core.models.GitStatus>(relaxed = true)
			every { mockStatus.conflicted } returns
				listOf(
					com.itsaky.androidide.git.core.models.FileChange(
						"conflicted.txt",
						com.itsaky.androidide.git.core.models.ChangeType.CONFLICTED,
					),
				)
			coEvery { repository.getStatus() } returns mockStatus

			viewModel.mergeBranch("feature-conflict")
			testScheduler.advanceTimeBy(100)

			val state = viewModel.mergeState.value
			assertTrue(state is GitBottomSheetViewModel.MergeUiState.Conflicts)
			val conflictState = state as GitBottomSheetViewModel.MergeUiState.Conflicts
			assertEquals("feature-conflict", conflictState.targetBranch)
			assertEquals(listOf("conflicted.txt"), conflictState.conflictingFiles)
		}

	@Test
	fun `mergeBranch error updates mergeState to Error`() =
		runTest {
			coEvery { repository.merge("non-existent") } throws IllegalArgumentException("Branch not found")

			viewModel.mergeBranch("non-existent")
			testScheduler.advanceTimeBy(100)

			val state = viewModel.mergeState.value
			assertTrue(state is GitBottomSheetViewModel.MergeUiState.Error)
			val errorState = state as GitBottomSheetViewModel.MergeUiState.Error
			assertEquals("non-existent", errorState.targetBranch)
			assertEquals("Branch not found", errorState.message)
		}

	@Test
	fun `fetchBranches error updates branches state to Error`() =
		runTest {
			coEvery { repository.getBranches() } throws RuntimeException("Git error")

			viewModel.fetchBranches()
			advanceUntilIdle()

			val state = viewModel.branches.value
			assertTrue(state is GitBottomSheetViewModel.BranchesUiState.Error)
			assertEquals("Git error", (state as GitBottomSheetViewModel.BranchesUiState.Error).message)
		}

	@Test
	fun `refreshStatus preserves gitStatus when branch fetching fails`() =
		runTest {
			val mockStatus = mockk<com.itsaky.androidide.git.core.models.GitStatus>(relaxed = true)
			coEvery { repository.getStatus() } returns mockStatus
			coEvery { repository.getCurrentBranch() } returns GitBranch("main", "refs/heads/main", true, false)
			coEvery { repository.getLocalCommitsCount() } returns 2
			coEvery { repository.getBranches() } throws RuntimeException("Branch failure")

			viewModel.refreshStatus()
			advanceUntilIdle()

			assertEquals(mockStatus, viewModel.gitStatus.value)
			assertEquals("main", viewModel.currentBranch.value)
			assertTrue(viewModel.branches.value is GitBottomSheetViewModel.BranchesUiState.Error)
		}

	@Test
	fun `initializeRepository clears currentRepository when opening fails`() =
		runTest {
			io.mockk.mockkObject(com.itsaky.androidide.projects.IProjectManager.Companion)
			val mockProjectManager = mockk<com.itsaky.androidide.projects.IProjectManager>(relaxed = true)
			every { mockProjectManager.projectDirPath } returns "/mock/path"
			every {
				com.itsaky.androidide.projects.IProjectManager
					.getInstance()
			} returns mockProjectManager

			io.mockk.mockkObject(com.itsaky.androidide.git.core.GitRepositoryManager)
			coEvery {
				com.itsaky.androidide.git.core.GitRepositoryManager
					.openRepository(any())
			} throws RuntimeException("Corrupt repository")

			viewModel.initializeRepository(force = true)
			advanceUntilIdle()

			assertEquals(null, viewModel.currentRepository)
			assertEquals(false, viewModel.isGitRepository.value)
			assertEquals(com.itsaky.androidide.git.core.models.GitStatus.EMPTY, viewModel.gitStatus.value)
			assertEquals(null, viewModel.currentBranch.value)
			assertEquals(GitBottomSheetViewModel.BranchesUiState.None, viewModel.branches.value)
		}
}
