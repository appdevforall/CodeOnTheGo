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
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.databinding.ActivityEditorBinding
import com.itsaky.androidide.databinding.ContentEditorBinding
import com.itsaky.androidide.deeplink.PendingDeepLinkOpen
import com.itsaky.androidide.models.PendingFileRequest
import com.itsaky.androidide.projects.IProjectManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
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
 * A deep link into the project that is already open, arriving while that project is still syncing
 * (`workspace == null`), must not be dropped: `switchToProject`'s same-project branch has to arm
 * the request on the intent so `postProjectInit`'s deferred retry finds it once the sync completes
 * (ADFA-5067 review).
 *
 * The failure mode being pinned is double: the new request used to die in a local variable, and
 * because `onNewIntent`'s carry-forward guard had already re-armed the *previous*, still-unconsumed
 * request onto the intent, `postProjectInit` then navigated to that stale target -- the link
 * appeared to work, at the wrong file.
 *
 * Mirrors [RestorePluginTabsThreadTest]'s approach of exercising a real, private production method
 * on an activity that has been built but not created -- creating the full editor activity is far
 * beyond what a JVM test can do, and everything this path touches (the intent, the project
 * manager, the binding null-check) can be provided directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = SameProjectDeepLinkMidSyncTest.TestApp::class)
class SameProjectDeepLinkMidSyncTest {
	open class TestApp : BaseApplication()

	// switchToProject reads the Koin-provided PendingDeepLinkOpen on three of its four branches, and
	// this activity is built but never created, so `isDestroyed` makes contentOrNull null and the
	// binding-torn-down branch is the one taken. Without a Koin context that branch threw
	// "KoinApplication has not been started" before the test could assert anything -- this test has
	// been failing on the branch for exactly that reason, independently of what it is meant to check.
	private var startedKoin = false

	// switchToProject reads the Koin-provided PendingDeepLinkOpen on three of its four branches, so a
	// Koin context has to exist. It may already: run in the full :app suite rather than alone, another
	// test's application has started one, and startKoin would throw
	// KoinApplicationAlreadyStartedException. Join the existing context in that case and leave it
	// running for whoever owns it; only tear down a context this test started itself.
	@Before
	fun setUp() {
		val binding = module { single { PendingDeepLinkOpen() } }
		val existing = GlobalContext.getOrNull()
		if (existing == null) {
			startedKoin = true
			startKoin { modules(binding) }
		} else {
			existing.loadModules(listOf(binding))
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

	@Test
	fun `a mid-sync same-project request stays armed and supersedes the carried-forward one`() {
		val projectPath = "/projects/MyApp"
		mockkObject(IProjectManager.Companion)
		val projectManager = mockk<IProjectManager>(relaxed = true)
		every { projectManager.projectDirPath } returns projectPath
		// The state under test: the project has started opening but the Gradle sync has not
		// completed, so the workspace is not available yet.
		every { projectManager.workspace } returns null
		every { IProjectManager.getInstance() } returns projectManager

		val activity =
			Robolectric
				.buildActivity(EditorHandlerActivity::class.java, Intent())
				.get()
		// Non-null binding AND a non-null `content` on it, so switchToProject takes its same-project
		// branch instead of the binding-torn-down handoff; nothing on the branch under test touches the
		// views themselves.
		//
		// `content` is set by reflection because view binding generates it as a public Java FIELD, and
		// mockk stubs methods, not fields -- a relaxed mock therefore leaves it null, `contentOrNull`
		// (which returns `_binding!!.content`) reads null, and the test silently exercised the
		// binding-torn-down branch instead of the one it names. That is why it has been failing.
		val activityBinding = mockk<ActivityEditorBinding>(relaxed = true)
		ActivityEditorBinding::class.java
			.getDeclaredField("content")
			.apply { isAccessible = true }
			.set(activityBinding, mockk<ContentEditorBinding>(relaxed = true))
		activity._binding = activityBinding

		// What onNewIntent's carry-forward guard re-arms from the previous intent: the earlier,
		// still-unconsumed request. Without the fix, postProjectInit would find (and navigate to)
		// this one.
		val staleRequest = PendingFileRequest("file/A.kt", null, null)
		activity.intent.putExtra(PendingFileRequest.EXTRA_KEY, staleRequest)

		val newRequest = PendingFileRequest("file/B.kt", "10", "2")
		val switchToProject =
			EditorHandlerActivity::class.java.getDeclaredMethod(
				"switchToProject",
				String::class.java,
				PendingFileRequest::class.java,
				String::class.java,
				// bookkeepingAlreadyRecorded: false here, since this exercises the deep-link path, where
				// MainActivity.openProject never ran and the open has not been recorded yet.
				Boolean::class.javaPrimitiveType,
			)
		switchToProject.isAccessible = true
		switchToProject.invoke(activity, projectPath, newRequest, projectPath, false)

		// postProjectInit's deferred retry reads exactly this extra once the sync completes: it
		// must find the new request -- not nothing, and not the stale carried-forward one.
		val armed =
			IntentCompat.getParcelableExtra(
				activity.intent,
				PendingFileRequest.EXTRA_KEY,
				PendingFileRequest::class.java,
			)
		assertThat(armed).isEqualTo(newRequest)
	}
}
