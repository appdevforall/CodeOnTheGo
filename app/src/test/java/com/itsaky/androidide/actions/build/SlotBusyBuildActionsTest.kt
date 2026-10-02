package com.itsaky.androidide.actions.build

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.fragments.RunTasksDialogFragment
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.quickbuild.FakeBuildService
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashInfoLong
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Run tasks and Sync project while Quick Build's eager prebuild holds the one Gradle slot.
 *
 * Both actions stay enabled for that internal build, because the user has no build running and a
 * greyed-out item gave no reason. A tap therefore reaches the action, and starting a second build
 * would throw BuildInProgressException, so the tap must flash the slot-busy message and start
 * nothing. Whether the flash is readable and the toolbar repaints is device-only.
 */
@RunWith(RobolectricTestRunner::class)
class SlotBusyBuildActionsTest {
	private val activity = mockk<EditorHandlerActivity>(relaxed = true)
	private val data = ActionData.create(activity)

	@Before
	fun stubFlash() {
		mockkStatic(FLASHBAR_UTILS)
		every { any<Activity>().flashInfoLong(any<String>()) } just runs
		every { activity.getString(R.string.msg_build_slot_busy) } returns BUSY_TEXT
	}

	@After
	fun tearDown() {
		unmockkStatic(FLASHBAR_UTILS)
		Lookup.getDefault().unregister(BuildService.KEY_BUILD_SERVICE)
	}

	private fun slot(
		busy: Boolean,
		userVisible: Boolean,
	) = Lookup.getDefault().update(
		BuildService.KEY_BUILD_SERVICE,
		FakeBuildService(busyAtRead = { busy }, userVisible = userVisible),
	)

	@Test
	fun `an internal build leaves Run tasks and Sync project enabled`() {
		slot(busy = true, userVisible = false)

		val runTasks = RunTasksAction(ApplicationProvider.getApplicationContext(), 0).apply { prepare(data) }
		val sync = ProjectSyncAction(ApplicationProvider.getApplicationContext(), 0).apply { prepare(data) }

		assertThat(runTasks.enabled).isTrue()
		assertThat(sync.enabled).isTrue()
	}

	@Test
	fun `a build the user started still greys both out`() {
		slot(busy = true, userVisible = true)

		val runTasks = RunTasksAction(ApplicationProvider.getApplicationContext(), 0).apply { prepare(data) }
		val sync = ProjectSyncAction(ApplicationProvider.getApplicationContext(), 0).apply { prepare(data) }

		assertThat(runTasks.enabled).isFalse()
		assertThat(sync.enabled).isFalse()
	}

	@Test
	fun `Run tasks tapped while the slot is busy flashes why and opens no dialog`() {
		slot(busy = true, userVisible = false)
		val action = RunTasksAction(ApplicationProvider.getApplicationContext(), 0)

		val result = runBlocking { action.execAction(data) }

		assertThat(result).isNotInstanceOf(RunTasksDialogFragment::class.java)
		coVerify(exactly = 0) { activity.saveAll(any(), any(), any(), any()) }
		verify(exactly = 1) { activity.flashInfoLong(BUSY_TEXT) }
	}

	@Test
	fun `Sync project tapped while the slot is busy flashes why and starts no sync`() {
		slot(busy = true, userVisible = false)
		val action = ProjectSyncAction(ApplicationProvider.getApplicationContext(), 0)

		runBlocking { action.execAction(data) }

		verify(exactly = 0) { activity.initializeProject(any<Boolean>()) }
		verify(exactly = 1) { activity.flashInfoLong(BUSY_TEXT) }
	}

	@Test
	fun `with the slot free both taps go through and nothing is flashed`() {
		slot(busy = false, userVisible = false)

		val result =
			runBlocking { RunTasksAction(ApplicationProvider.getApplicationContext(), 0).execAction(data) }
		runBlocking { ProjectSyncAction(ApplicationProvider.getApplicationContext(), 0).execAction(data) }

		assertThat(result).isInstanceOf(RunTasksDialogFragment::class.java)
		verify(exactly = 1) { activity.initializeProject(forceSync = true) }
		verify(exactly = 0) { activity.flashInfoLong(any<String>()) }
	}

	private companion object {
		const val FLASHBAR_UTILS = "com.itsaky.androidide.utils.FlashbarActivityUtilsKt"
		const val BUSY_TEXT = "slot busy"
	}
}
