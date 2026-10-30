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

package com.itsaky.androidide.security

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import com.itsaky.androidide.resources.R as ResR

@RunWith(RobolectricTestRunner::class)
class InstantHideTest {
	private class FakeState(
		override var armed: Boolean = false,
		override var wipeArmed: Boolean = false,
		override var hidden: Boolean = false,
		var wiped: Boolean = false,
	) : HideState {
		override fun neutralized(context: Context): Boolean = wiped
	}

	private val state = FakeState()
	private var hides = 0
	private val context: Context = RuntimeEnvironment.getApplication()

	@Before
	fun mount() {
		InstantHide.state = state
		InstantHide.register(RuntimeEnvironment.getApplication()) { hides++ }
	}

	@After
	fun unmount() {
		InstantHide.state = PreferencesHideState
	}

	private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

	/** A resumed, focused activity with no base class of the IDE's, as the terminal is. */
	private fun focusedActivity(): ActivityController<Activity> =
		Robolectric
			.buildActivity(Activity::class.java)
			.setup()
			.windowFocusChanged(true)

	private fun pressKey(activity: Activity) {
		activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
	}

	@Test
	fun `while hidden, any activity is replaced by the disguise as it is created`() {
		state.hidden = true

		val activity = Robolectric.buildActivity(Activity::class.java).create().get()

		assertThat(activity.isFinishing).isTrue()
		val next = shadowOf(activity).nextStartedActivity
		assertThat(next.component?.className).isEqualTo(CryptogramActivity::class.java.name)
	}

	@Test
	fun `a wiped install sends every activity to the disguise even with the hidden flag clear`() {
		state.wiped = true

		val activity = Robolectric.buildActivity(Activity::class.java).create().get()

		assertThat(activity.isFinishing).isTrue()
		assertThat(shadowOf(activity).nextStartedActivity.component?.className)
			.isEqualTo(CryptogramActivity::class.java.name)
	}

	@Test
	fun `a redirect while hidden reuses the running disguise instead of clearing its task`() {
		state.hidden = true

		val activity = Robolectric.buildActivity(Activity::class.java).create().get()

		val flags = shadowOf(activity).nextStartedActivity.flags
		assertThat(flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isEqualTo(0)
		assertThat(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
		assertThat(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
	}

	@Test
	fun `an armed screen with no IDE base class gets FLAG_SECURE`() {
		state.armed = true

		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

		assertThat(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
	}

	@Test
	fun `typing keeps an armed screen from hiding for inactivity`() {
		state.armed = true
		val activity = focusedActivity().get()

		idle(50)
		pressKey(activity)
		idle(50)

		assertThat(state.hidden).isFalse()
	}

	@Test
	fun `an armed screen with no input hides after a minute and closes the floating windows`() {
		state.armed = true
		val activity = focusedActivity().get()

		idle(61)

		assertThat(state.hidden).isTrue()
		assertThat(hides).isEqualTo(1)
		assertThat(shadowOf(activity).nextStartedActivity.component?.className)
			.isEqualTo(CryptogramActivity::class.java.name)
	}

	@Test
	fun `one screen-off broadcast hides once, however many IDE screens are open`() {
		state.armed = true
		// The screen below keeps its screen-off receiver while paused, as MainActivity under the editor.
		Robolectric.buildActivity(Activity::class.java).setup()
		Robolectric.buildActivity(Activity::class.java).setup()

		context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
		shadowOf(Looper.getMainLooper()).idle()

		assertThat(state.hidden).isTrue()
		assertThat(hides).isEqualTo(1)
	}

	@Test
	fun `a screen whose dialog or popup has the focus does not hide for inactivity until it is back`() {
		state.armed = true
		val controller = focusedActivity()

		controller.windowFocusChanged(false)
		idle(130)
		assertThat(state.hidden).isFalse()

		controller.windowFocusChanged(true)
		idle(61)
		assertThat(state.hidden).isTrue()
	}

	@Test
	fun `masking removes ordinary notifications and neutralises a foreground service's`() {
		val manager = context.getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(NotificationChannel("c", "c", NotificationManager.IMPORTANCE_LOW))
		val service =
			Notification
				.Builder(context, "c")
				.setSmallIcon(ResR.drawable.ic_stealth_notification)
				.setContentTitle("Building MyApp")
				.build()
				.apply { flags = flags or Notification.FLAG_FOREGROUND_SERVICE }
		manager.notify(1, service)
		manager.notify(
			2,
			Notification
				.Builder(context, "c")
				.setSmallIcon(ResR.drawable.ic_stealth_notification)
				.setContentTitle("Build finished")
				.build(),
		)

		NeutralNotifications.mask(context)

		val shown = manager.activeNotifications
		assertThat(shown.map { it.id }).containsExactly(1)
		assertThat(
			shown
				.single()
				.notification.extras
				.getString(Notification.EXTRA_TITLE),
		).isEqualTo(context.getString(ResR.string.stealth_app_name))
	}
}
