package com.itsaky.androidide.activities.editor

import android.content.Intent
import android.widget.ViewFlipper
import com.google.android.material.tabs.TabLayout
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.databinding.ActivityEditorBinding
import com.itsaky.androidide.databinding.ContentEditorBinding
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Selecting a plugin tab that is already open shows no file (ADFA-7141), as adding one does, so
 * listeners hear null rather than keep the file shown before it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = PluginTabSelectionTest.TestApp::class)
class PluginTabSelectionTest {
	open class TestApp : BaseApplication()

	@Test
	fun selectingAnOpenPluginTabShowsNoFile() {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java, Intent()).get()
		val content = mockk<ContentEditorBinding>(relaxed = true)
		// The binding's views are public Java fields, which mockk cannot stub.
		ContentEditorBinding::class.java
			.getDeclaredField("tabs")
			.apply { isAccessible = true }
			.set(content, mockk<TabLayout>(relaxed = true) { every { tabCount } returns 3 })
		ContentEditorBinding::class.java
			.getDeclaredField("editorContainer")
			.apply { isAccessible = true }
			.set(content, mockk<ViewFlipper>(relaxed = true))
		val binding = mockk<ActivityEditorBinding>(relaxed = true)
		ActivityEditorBinding::class.java
			.getDeclaredField("content")
			.apply { isAccessible = true }
			.set(binding, content)
		activity._binding = binding
		// Two file tabs, then a plugin's tab at position 2.
		@Suppress("UNCHECKED_CAST")
		val pluginTabs =
			EditorHandlerActivity::class.java
				.getDeclaredField("tabIndexToPluginId")
				.apply { isAccessible = true }
				.get(activity) as MutableMap<Int, String>
		pluginTabs[2] = "some.plugin.tab"
		val a = File("/project/A.kt")
		activity.editorViewModel.addFile(a)
		activity.editorViewModel.addFile(File("/project/B.kt"))
		activity.editorViewModel.selectFile(0, a)

		activity.onTabSelected(mockk<TabLayout.Tab> { every { position } returns 2 })

		assertThat(activity.editorViewModel.displayedFileIndex).isEqualTo(-1)
		assertThat(activity.editorViewModel.displayedFile()).isNull()
	}
}
