package com.itsaky.androidide.activities.editor

import android.app.Activity
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_TOOLBAR
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.build.QuickBuildAction
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.quickbuild.GradleQuickBuildProvisioner
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.flashInfoLong
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildTone
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap on the "tap for details" Quick Build status line does what a tap on the button does
 * (ADFA-7138, F23): since ADFA-6574 a tap on the greyed-out button flashes why it is greyed out,
 * so a tap on the line must flash the same reason, not do nothing.
 *
 * Drives the activity's status-line callback with a Quick Build action registered as the toolbar
 * would leave it. The reason comes from the real [QuickBuildAction.disabledReason]; only its
 * inputs are stubbed: a release variant selected, no Quick Build session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = QuickBuildGreyedStatusLineTapTest.TestApp::class)
class QuickBuildGreyedStatusLineTapTest {
	open class TestApp : BaseApplication()

	private val registry = ActionsRegistry.getInstance()
	private var previous: ActionItem? = null
	private lateinit var activity: EditorHandlerActivity

	@Before
	fun setUp() {
		previous = registry.findAction(EDITOR_TOOLBAR, QuickBuildAction.ID)
		mockkObject(IProjectManager.Companion)
		every { IProjectManager.getInstance() } returns mockk(relaxed = true)
		mockkObject(GradleQuickBuildProvisioner.Companion)
		every { GradleQuickBuildProvisioner.selectedVariantName() } returns "release"
		mockkObject(QuickBuildAction.Companion)
		every { QuickBuildAction.currentTone() } returns QuickBuildTone.READY
		mockkStatic("com.itsaky.androidide.utils.FlashbarActivityUtilsKt")
		every { any<Activity>().flashInfoLong(any()) } just runs
		activity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()
	}

	@After
	fun tearDown() {
		registry.unregisterAction(QuickBuildAction.ID)
		previous?.let(registry::registerAction)
		unmockkAll()
	}

	private fun registerQuickBuild(enabled: Boolean): ActionItem {
		val action = mockk<ActionItem>(relaxed = true)
		every { action.id } returns QuickBuildAction.ID
		every { action.location } returns EDITOR_TOOLBAR
		every { action.enabled } returns enabled
		registry.registerAction(action)
		return action
	}

	/** What the bottom sheet's status line calls when the user taps it. */
	private fun tapStatusLine() {
		EditorHandlerActivity::class.java
			.getDeclaredMethod("onQuickBuildStatusLineTapped")
			.apply { isAccessible = true }
			.invoke(activity)
	}

	@Test
	fun `a status-line tap while Quick Build is greyed out for a release variant flashes why`() {
		val quickBuild = registerQuickBuild(enabled = false)

		tapStatusLine()

		val reason = activity.getString(R.string.quick_build_non_debuggable_variant, "release")
		verify(exactly = 1) { activity.flashInfoLong(reason) }
		coVerify(exactly = 0) { quickBuild.execAction(any()) }
	}

	@Test
	fun `a status-line tap while Quick Build is enabled runs it and flashes nothing`() {
		val quickBuild = registerQuickBuild(enabled = true)

		tapStatusLine()

		coVerify(timeout = 5_000) { quickBuild.execAction(any()) }
		verify(exactly = 0) { activity.flashInfoLong(any()) }
	}
}
