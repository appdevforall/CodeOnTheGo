package com.itsaky.androidide.utils

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class FlashErrorStringResTest {
	private lateinit var activity: AppCompatActivity

	@Before
	fun setUp() {
		FeatureFlags.resetForTest()
		activity =
			Robolectric
				.buildActivity(AppCompatActivity::class.java)
				.also { it.get().setTheme(R.style.Theme_AndroidIDE) }
				.setup()
				.get()
	}

	@After
	fun tearDown() {
		FeatureFlags.resetForTest()
	}

	@Test
	fun `string resource error stays until dismissed`() {
		activity.flashError(R.string.msg_export_not_built)
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

		val root = activity.window.decorView
		assertThat(root.findText(activity.getString(R.string.msg_export_not_built))).isNotNull()
		val dismiss = root.findText(activity.getString(R.string.dismiss))
		assertThat(dismiss).isNotNull()
		assertThat(dismiss!!.visibility).isEqualTo(View.VISIBLE)

		dismiss.performClick()
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

		assertThat(root.findText(activity.getString(R.string.msg_export_not_built))).isNull()
	}

	private fun View.findText(text: String): TextView? {
		if (this is TextView && this.text.toString() == text) return this
		if (this !is ViewGroup) return null
		for (i in 0 until childCount) {
			getChildAt(i).findText(text)?.let { return it }
		}
		return null
	}
}
