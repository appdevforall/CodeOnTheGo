package com.itsaky.androidide.activities.editor

import android.util.TypedValue
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.preferences.internal.EditorPreferences
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How [EditorHandlerActivity] feeds [PluginTextScaler] (ADFA-7142): which fragments it tracks and
 * which preference changes rescale them. The activity is built but not created, as in the other
 * activity tests here, so its own handlers run without the editor's views. Two calls need a
 * created activity and are not reached: the callbacks' registration in `onCreate`, and the
 * re-read in `onStart` (a direct `onStart` fails in FragmentManager without one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EditorPluginTextScalingTest.TestApp::class)
class EditorPluginTextScalingTest {
	open class TestApp : BaseApplication()

	/** Stands in for a plugin's fragment once [pluginFragment] loads it through its own class loader. */
	class StandInFragment : Fragment()

	@Test
	fun onlyAPluginFragmentsViewIsScaled() {
		EditorPreferences.fontSize = 28f
		val activity = activity()
		val pluginText = textView(activity)
		val ideText = textView(activity)

		fragmentCallbacks(activity).run {
			onFragmentViewCreated(activity.supportFragmentManager, pluginFragment(), pluginText, null)
			onFragmentViewCreated(activity.supportFragmentManager, StandInFragment(), ideText, null)
		}

		assertThat(pluginText.textSize).isEqualTo(40f)
		assertThat(ideText.textSize).isEqualTo(20f)
	}

	@Test
	fun aFontSizeChangeRescalesPluginTextAndOtherPreferencesDoNot() {
		val activity = activity()
		val text = textView(activity)
		fragmentCallbacks(activity).onFragmentViewCreated(activity.supportFragmentManager, pluginFragment(), text, null)
		assertThat(text.textSize).isEqualTo(20f)

		EditorPreferences.fontSize = 28f
		activity.onPreferenceChanged(PreferenceChangeEvent(EditorPreferences.TAB_SIZE, 8))
		assertThat(text.textSize).isEqualTo(20f)

		activity.onPreferenceChanged(PreferenceChangeEvent(EditorPreferences.FONT_SIZE, 28f))
		assertThat(text.textSize).isEqualTo(40f)
	}

	// Pinch-zoom saves through PreferenceManager, which posts the event; this is what receives it.
	@Test
	fun theFontSizeHandlerIsSubscribedOnTheMainThread() {
		val subscribe =
			EditorHandlerActivity::class.java
				.getMethod("onPreferenceChanged", PreferenceChangeEvent::class.java)
				.getAnnotation(Subscribe::class.java)

		assertThat(subscribe).isNotNull()
		assertThat(subscribe!!.threadMode).isEqualTo(ThreadMode.MAIN)
	}

	private fun activity(): EditorHandlerActivity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()

	private fun textView(activity: EditorHandlerActivity) = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f) }

	private fun fragmentCallbacks(activity: EditorHandlerActivity) =
		EditorHandlerActivity::class.java
			.getDeclaredField("pluginFontScalingListener")
			.apply { isAccessible = true }
			.get(activity) as FragmentManager.FragmentLifecycleCallbacks

	// The activity tells a plugin's fragment by its class loader, so define the stand-in again in
	// a loader of its own, the way the plugin manager loads a .cgp.
	private fun pluginFragment(): Fragment {
		val name = StandInFragment::class.java.name
		val parent = StandInFragment::class.java.classLoader!!
		val loader =
			object : ClassLoader(parent) {
				override fun loadClass(
					className: String,
					resolve: Boolean,
				): Class<*> {
					if (className != name) return super.loadClass(className, resolve)
					synchronized(this) {
						findLoadedClass(className)?.let { return it }
						val bytes = parent.getResourceAsStream(className.replace('.', '/') + ".class")!!.use { it.readBytes() }
						return defineClass(className, bytes, 0, bytes.size)
					}
				}
			}
		return loader.loadClass(name).getDeclaredConstructor().newInstance() as Fragment
	}
}
