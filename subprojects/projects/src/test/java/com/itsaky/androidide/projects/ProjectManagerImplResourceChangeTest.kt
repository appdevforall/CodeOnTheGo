package com.itsaky.androidide.projects

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A resource file created or deleted in the file tree must send its `generateSources` run
 * through [ProjectManagerImpl.resourceChangeBuild], the seam the app points at Quick Build's
 * generate-sources deferral. Calling [ProjectManagerImpl.generateSources] directly bypasses the
 * deferral: the build lands under a live session, is handed back as an external build, and the
 * session's next save is a full recompile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProjectManagerImplResourceChangeTest {
	private val layout = File("app/src/main/res/layout/foo.xml")

	private fun managerSeeingResources(): ProjectManagerImpl =
		spyk(ProjectManagerImpl()).also { manager ->
			every { manager.isAndroidResource(any()) } returns true
		}

	@Test
	fun `a created resource file routes its build through the seam, not the direct call`() {
		val manager = managerSeeingResources()
		var routed = 0
		manager.resourceChangeBuild = { routed++ }

		manager.onFileCreated(FileCreationEvent(layout))

		assertThat(routed).isEqualTo(1)
		verify(exactly = 0) { manager.generateSources(any()) }
	}

	@Test
	fun `a deleted resource file routes through the same seam`() {
		val manager = managerSeeingResources()
		var routed = 0
		manager.resourceChangeBuild = { routed++ }

		manager.onFileDeleted(FileDeletionEvent(layout))

		assertThat(routed).isEqualTo(1)
	}

	@Test
	fun `a file outside the resource directories requests no build`() {
		val manager = spyk(ProjectManagerImpl())
		every { manager.isAndroidResource(any()) } returns false
		var routed = 0
		manager.resourceChangeBuild = { routed++ }

		manager.onFileCreated(FileCreationEvent(File("app/src/main/java/Foo.kt")))

		assertThat(routed).isEqualTo(0)
	}
}
