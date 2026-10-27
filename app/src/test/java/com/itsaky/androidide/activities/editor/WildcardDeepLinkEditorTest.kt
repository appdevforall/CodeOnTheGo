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

	// A different project's switch is waiting on its close dialog, so the intent being replaced
	// carries THAT project's file request. A wildcard must not re-arm it onto its own intent, or
	// cancelling the close would apply the other project's file inside the staying one.
	@Test
	fun `a wildcard under an open close dialog does not inherit the pending switch's file request`() {
		projectManager("/anywhere/MyApp", ready = true)
		val activity = activity()
		EditorHandlerActivity::class.java
			.getDeclaredField("confirmCloseInProgress")
			.apply { isAccessible = true }
			.setBoolean(activity, true)
		activity.intent.putExtra(PendingFileRequest.EXTRA_KEY, PendingFileRequest("other/B.kt", null, null))

		val incoming = Intent().putExtra(DeepLinkRequest.EXTRA_KEY, wildcard)
		EditorHandlerActivity::class.java
			.getDeclaredMethod("onNewIntent", Intent::class.java)
			.apply { isAccessible = true }
			.invoke(activity, incoming)

		assertThat(activity.intent.hasExtra(PendingFileRequest.EXTRA_KEY)).isFalse()
		verify(exactly = 0) { pending.arm(any(), any()) }
	}

	private fun EditorHandlerActivity.field(name: String) =
		EditorHandlerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

	private fun EditorHandlerActivity.captureForSwitch() {
		field("capturedPendingFileRequestBeforeSwitch").setBoolean(this, true)
		field("pendingFileRequestBeforeSwitch").set(this, PendingFileRequest("staying/A.kt", null, null))
		field("stayingProjectPathBeforeSwitch").set(this, "/anywhere/MyApp")
	}

	private fun EditorHandlerActivity.deliver(request: DeepLinkRequest) {
		EditorHandlerActivity::class.java
			.getDeclaredMethod("onNewIntent", Intent::class.java)
			.apply { isAccessible = true }
			.invoke(this, Intent().putExtra(DeepLinkRequest.EXTRA_KEY, request))
	}

	// A named switch still resolving has captured the staying project's state. A wildcard supersedes
	// it, so that lookup exits as "superseded" and restores nothing -- the wildcard has to roll the
	// capture back, or the next real switch skips its own.
	@Test
	fun `a wildcard superseding a resolving switch clears that switch's capture`() {
		projectManager("/anywhere/MyApp", ready = true)
		val activity = activity()
		activity.captureForSwitch()

		activity.deliver(wildcard)

		assertThat(activity.field("capturedPendingFileRequestBeforeSwitch").getBoolean(activity)).isFalse()
		assertThat(activity.field("stayingProjectPathBeforeSwitch").get(activity)).isNull()
	}

	@Test
	fun `a wildcard leaves the capture alone while a close dialog owns it`() {
		projectManager("/anywhere/MyApp", ready = true)
		val activity = activity()
		activity.captureForSwitch()
		activity.field("confirmCloseInProgress").setBoolean(activity, true)

		activity.deliver(wildcard)

		assertThat(activity.field("capturedPendingFileRequestBeforeSwitch").getBoolean(activity)).isTrue()
		assertThat(activity.field("stayingProjectPathBeforeSwitch").get(activity)).isEqualTo("/anywhere/MyApp")
	}

	// The project path is read once, on the calling thread: a switch landing before the IO thread
	// starts must not change which project the file is resolved against.
	@Test
	fun `the project path is read on the calling thread, not inside the IO coroutine`() {
		val readers = java.util.concurrent.CopyOnWriteArrayList<Thread>()
		mockkObject(IProjectManager.Companion)
		val manager = mockk<IProjectManager>(relaxed = true)
		every { manager.projectDirPath } answers {
			readers.add(Thread.currentThread())
			"/anywhere/MyApp"
		}
		every { manager.workspace } returns mockk(relaxed = true)
		every { IProjectManager.getInstance() } returns manager
		val activity = activity()

		EditorHandlerActivity::class.java
			.getDeclaredMethod("applyDeepLinkFileRequest", PendingFileRequest::class.java)
			.apply { isAccessible = true }
			.invoke(activity, file)
		Thread.sleep(500)
		org.robolectric.shadows.ShadowLooper
			.idleMainLooper()

		assertThat(readers).isNotEmpty()
		assertThat(readers.toSet()).containsExactly(Thread.currentThread())
	}
}
