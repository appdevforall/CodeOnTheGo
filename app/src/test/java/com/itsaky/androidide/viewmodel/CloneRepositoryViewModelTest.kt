package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.itsaky.androidide.R
import com.itsaky.androidide.git.core.GitRepositoryManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import com.itsaky.androidide.git.core.models.CloneRepoUiState


@RunWith(JUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)

class CloneRepositoryViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var viewModel: CloneRepositoryViewModel
    private val context = mockk<Application>(relaxed = true)

    @Before
    fun setup() {
        mockkObject(GitRepositoryManager)
        viewModel = CloneRepositoryViewModel(context)

        // Mock the application string responses for error messages
        every { context.getString(any(), *anyVararg()) } answers {
            val varargs = it.invocation.args[1] as Array<*>
            "Clone failed: ${varargs[0]}"
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initial state should be empty and clone button disabled`() {
        val state = viewModel.uiState.value
        assertTrue(state is CloneRepoUiState.Idle)
        assertEquals("", (state as CloneRepoUiState.Idle).url)
        assertEquals("", state.localPath)
        assertFalse(state.isCloneButtonEnabled)
    }

    @Test
    fun `when state is reset, values are cleared`() {
        viewModel.onInputChanged(
            url = "https://github.com/user/repo.git", path = "/sdcard/path"
        )

        viewModel.resetState()

        val state = viewModel.uiState.value
        assertTrue(state is CloneRepoUiState.Idle)
        assertEquals("", (state as CloneRepoUiState.Idle).url)
        assertEquals("", state.localPath)
        assertFalse(state.isCloneButtonEnabled)
    }

    @Test
    fun `when input is valid, clone button is enabled`() {
        viewModel.onInputChanged(
            url = "https://github.com/user/repo.git", path = "/sdcard/path"
        )

        val state = viewModel.uiState.value
        assertTrue(state is CloneRepoUiState.Idle)
        assertTrue((state as CloneRepoUiState.Idle).isCloneButtonEnabled)
    }


    @Test
    fun `when input is invalid, clone button is disabled`() {
        viewModel.onInputChanged(
            url = "", path = ""
        )

        val state = viewModel.uiState.value
        assertTrue(state is CloneRepoUiState.Idle)
        assertFalse((state as CloneRepoUiState.Idle).isCloneButtonEnabled)
    }

    @Test
    fun `cloning into non-empty directory sets error status`() {
        val folder = tempFolder.newFolder("existing_repo")
        File(folder, "keep.me").createNewFile()

        viewModel.cloneRepository("https://github.com/username/newproject.git", folder.absolutePath)

        val state = viewModel.uiState.value
        assertTrue(state is CloneRepoUiState.Error)
        assertEquals(R.string.destination_directory_not_empty, (state as CloneRepoUiState.Error).errorResId)
    }

    @Test
    fun `cloneRepository success updates UI state correctly`() = runTest {
        val folder = tempFolder.newFolder("NewProject")
        val url = "https://github.com/username/newproject.git"

        coEvery {
            GitRepositoryManager.cloneRepository(any(), any(), any(), any())
        } returns mockk()

        viewModel.cloneRepository(url, folder.absolutePath)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CloneRepoUiState.Success)
        assertEquals(folder.absolutePath, (state as CloneRepoUiState.Success).localPath)

        coVerify(exactly = 1) {
            GitRepositoryManager.cloneRepository(
                url = url, destDir = any(), credentialsProvider = null, progressMonitor = any()
            )
        }
    }

    @Test
    fun `cloneRepository failure updates UI with error message`() = runTest {
        val folder = tempFolder.newFolder("NewProject")
        val errorMsg = "Network Timeout"

        coEvery {
            GitRepositoryManager.cloneRepository(any(), any(), any(), any())
        } throws Exception(errorMsg)

        viewModel.cloneRepository("https://github.com/username/newproject.git", folder.absolutePath)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CloneRepoUiState.Error)
        Assert.assertNotNull((state as CloneRepoUiState.Error).errorMessage)
        assertTrue(state.errorMessage!!.contains(errorMsg))
    }

    @Test
    fun `test clone repository with auth is successful`() = runTest {
        val folder = tempFolder.newFolder("AuthProject")
        val url = "https://github.com/username/newproject.git"

        coEvery {
            GitRepositoryManager.cloneRepository(any(), any(), any(), any())
        } returns mockk()

        viewModel.cloneRepository(
            url = url,
            localPath = folder.absolutePath,
            username = "username",
            token = "token"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CloneRepoUiState.Success)

        coVerify(exactly = 1) {
            GitRepositoryManager.cloneRepository(
                url = url,
                destDir = folder,
                credentialsProvider = ofType(UsernamePasswordCredentialsProvider::class),
                progressMonitor = any()
            )
        }
    }
}
