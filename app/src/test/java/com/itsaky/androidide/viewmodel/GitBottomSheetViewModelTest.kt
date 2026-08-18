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
        viewModel = GitBottomSheetViewModel(credentialsManager, isNetworkConnected = { true })
        // Inject mock repository manually
        val field = GitBottomSheetViewModel::class.java.getDeclaredField("currentRepository")
        field.isAccessible = true
        field.set(viewModel, repository)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fetchBranches updates branches state`() = runTest {
        val mockBranches = listOf(
            GitBranch(name = "main", fullName = "refs/heads/main", isCurrent = true, isRemote = false),
            GitBranch(name = "feature", fullName = "refs/heads/feature", isCurrent = false, isRemote = false)
        )
        coEvery { repository.getBranches() } returns mockBranches

        viewModel.fetchBranches()
        advanceUntilIdle()

        assertEquals(mockBranches, viewModel.branches.value)
    }

    @Test
    fun `checkoutBranch success updates checkoutState to Success and then resets to Idle`() = runTest {
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
    fun `checkoutBranch conflict updates checkoutState to Conflicts and then resets to Idle`() = runTest {
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
}
