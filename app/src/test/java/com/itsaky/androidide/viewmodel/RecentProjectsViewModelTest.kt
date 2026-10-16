package com.itsaky.androidide.viewmodel

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectDao
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import com.itsaky.androidide.utils.canonicalProjectLocation
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RecentProjectsViewModelTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var viewModel: RecentProjectsViewModel
	private lateinit var dao: RecentProjectDao

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		viewModel = RecentProjectsViewModel(application)
		dao = RecentProjectRoomDatabase.getDatabase(application, viewModel.viewModelScope).recentProjectDao()
		runBlocking { dao.deleteAll() }
	}

	@Test
	fun `a project deleted from storage is dropped from Recents and from the database`() =
		runBlocking<Unit> {
			val projectsRoot = tempFolder.newFolder("CodeOnTheGoProjects")
			insert(File(projectsRoot, "Kept").apply { mkdirs() })
			insert(File(projectsRoot, "Deleted"))

			assertThat(loadedProjectNames()).containsExactly("Kept")
			assertThat(dao.dumpAll().orEmpty().map { it.name }).containsExactly("Kept")
		}

	@Test
	fun `a project whose parent folder is unavailable stays in Recents`() =
		runBlocking<Unit> {
			val unmountedVolume = File(tempFolder.root, "unmounted-sd-card")
			insert(File(unmountedVolume, "OnCard"))

			assertThat(loadedProjectNames()).containsExactly("OnCard")
			assertThat(dao.dumpAll().orEmpty().map { it.name }).containsExactly("OnCard")
		}

	private suspend fun insert(projectDir: File) {
		dao.insert(
			RecentProject(
				name = projectDir.name,
				createdAt = "0",
				location = projectDir.canonicalProjectLocation(),
			),
		)
	}

	private suspend fun loadedProjectNames(): List<String> {
		viewModel.loadProjects().join()
		shadowOf(Looper.getMainLooper()).idle()
		return viewModel.projects.value
			.orEmpty()
			.map { it.name }
	}
}
