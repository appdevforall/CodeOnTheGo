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

package com.itsaky.androidide.activities.editor

import android.content.Intent
import androidx.core.content.IntentCompat
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.analytics.DeepLinkMetric
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.databinding.ActivityEditorBinding
import com.itsaky.androidide.databinding.ContentEditorBinding
import com.itsaky.androidide.deeplink.PendingDeepLinkOpen
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.models.PendingFileRequest
import com.itsaky.androidide.projects.IProjectManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A wildcard deep link (`project/<*>`, ADFA-6773) is answered from the live project and must never
 * go through `switchToProject`: its finishing and blank-path branches arm a hand-off to a project
 * path that a wildcard does not have, which would finish the editor or reopen a project the user
 * just closed.
 *
 * Same approach as [SameProjectDeepLinkMidSyncTest]: a built-but-not-created activity, with the
 * project manager, binding and Koin collaborators provided directly. The ready-and-applying branch
 * hits disk and the editor's views, so it is covered on a device instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = WildcardDeepLinkEditorTest.TestApp::class)
class WildcardDeepLinkEditorTest {
	open class TestApp : BaseApplication()

	private val pending = mockk<PendingDeepLinkOpen>(relaxed = true)
	private val analytics = mockk<IAnalyticsManager>(relaxed = true)
	private var startedKoin = false

	@Before
	fun setUp() {
		val binding =
			module {
				single { pending }
				single { analytics }
			}
		val existing = GlobalContext.getOrNull()
		if (existing == null) {
			startedKoin = true
			startKoin { modules(binding) }
		} else {
			existing.loadModules(listOf(binding), allowOverride = true)
		}
	}

	@After
	fun tearDown() {
		if (startedKoin) {
			stopKoin()
			startedKoin = false
		}
		unmockkAll()
	}

	private fun projectManager(
		path: String,
		ready: Boolean,
	) {
		mockkObject(IProjectManager.Companion)
		val manager = mockk<IProjectManager>(relaxed = true)
		every { manager.projectDirPath } returns path
		every { manager.workspace } returns if (ready) mockk(relaxed = true) else null
		every { IProjectManager.getInstance() } returns manager
	}

	private fun activity(withViews: Boolean = true): EditorHandlerActivity {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java, Intent()).get()
		if (withViews) {
			// `content` is a public Java field on the generated binding, which mockk cannot stub.
			val activityBinding = mockk<ActivityEditorBinding>(relaxed = true)
			ActivityEditorBinding::class.java
				.getDeclaredField("content")
				.apply { isAccessible = true }
				.set(activityBinding, mockk<ContentEditorBinding>(relaxed = true))
			activity._binding = activityBinding
		}
		return activity
	}

	private fun EditorHandlerActivity.open(request: DeepLinkRequest) {
		EditorHandlerActivity::class.java
			.getDeclaredMethod("openInCurrentProject", DeepLinkRequest::class.java)
			.apply { isAccessible = true }
			.invoke(this, request)
	}

	private val file = PendingFileRequest("lesson/A.kt", "10", "2", viaWildcard = true)
	private val wildcard = DeepLinkRequest(DeepLinkRequest.WILDCARD_PROJECT, file)

	private fun assertNoProjectOpenReported() {
		val metric = mutableListOf<DeepLinkMetric>()
		verify { analytics.trackDeepLink(capture(metric)) }
		val bundle = metric.single().asBundle()
		assertThat(bundle.getString("outcome")).isEqualTo("no_project_open")
		assertThat(bundle.getString("selector")).isEqualTo("current")
	}

	@Test
	fun `mid-sync, the request is armed for the sync to apply`() {
		projectManager("/anywhere/MyApp", ready = false)
		val activity = activity()
		activity.intent.putExtra(PendingFileRequest.EXTRA_KEY, PendingFileRequest("stale.kt", null, null))

		activity.open(wildcard)

		val armed = IntentCompat.getParcelableExtra(activity.intent, PendingFileRequest.EXTRA_KEY, PendingFileRequest::class.java)
		assertThat(armed).isEqualTo(file)
		verify(exactly = 0) { pending.arm(any(), any()) }
		assertThat(activity.isFinishing).isFalse()
	}

	@Test
	fun `a link with no file does nothing else to a project that is open`() {
		projectManager("/anywhere/MyApp", ready = false)
		val activity = activity()

		activity.open(DeepLinkRequest(DeepLinkRequest.WILDCARD_PROJECT))

		assertThat(activity.intent.hasExtra(PendingFileRequest.EXTRA_KEY)).isFalse()
		verify(exactly = 0) { pending.arm(any(), any()) }
		verify(exactly = 0) { analytics.trackDeepLink(any()) }
		assertThat(activity.isFinishing).isFalse()
	}

	@Test
	fun `a close dialog already showing refuses the request and arms nothing`() {
		projectManager("/anywhere/MyApp", ready = true)
		val activity = activity()
		EditorHandlerActivity::class.java
			.getDeclaredField("confirmCloseInProgress")
			.apply { isAccessible = true }
			.setBoolean(activity, true)

		activity.open(wildcard)

		assertThat(activity.intent.hasExtra(PendingFileRequest.EXTRA_KEY)).isFalse()
		verify(exactly = 0) { pending.arm(any(), any()) }
		assertThat(activity.isFinishing).isFalse()
	}

	@Test
	fun `an editor finishing because a switch was confirmed reports no project and never re-arms the hand-off`() {
		projectManager("/anywhere/MyApp", ready = true)
		val activity = activity()
		activity.finish()

		activity.open(wildcard)

		verify(exactly = 0) { pending.arm(any(), any()) }
		assertNoProjectOpenReported()
	}

	@Test
	fun `an editor with no project path reports no project, arms nothing and does not finish`() {
		projectManager("", ready = false)
		val activity = activity()

		activity.open(wildcard)

		verify(exactly = 0) { pending.arm(any(), any()) }
		assertThat(activity.isFinishing).isFalse()
		assertNoProjectOpenReported()
	}

	@Test
	fun `an editor whose views are gone reports no project`() {
		projectManager("/anywhere/MyApp", ready = true)
		val activity = activity(withViews = false)

		activity.open(wildcard)

		verify(exactly = 0) { pending.arm(any(), any()) }
		assertNoProjectOpenReported()
	}
}
