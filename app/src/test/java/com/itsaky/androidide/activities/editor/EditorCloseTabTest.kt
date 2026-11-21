package com.itsaky.androidide.activities.editor

import android.view.View
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.databinding.ActivityEditorBinding
import com.itsaky.androidide.databinding.ContentEditorBinding
import com.itsaky.androidide.ui.CodeEditorView
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies tab closing and current file synchronization (ADFA-7144).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EditorCloseTabTest.TestApp::class)
class EditorCloseTabTest {
	open class TestApp : BaseApplication()

	private lateinit var activity: EditorHandlerActivity
	private lateinit var binding: ActivityEditorBinding

	private val fileA = File("/tmp/test/A.kt")
	private val fileB = File("/tmp/test/B.kt")
	private val fileC = File("/tmp/test/C.kt")

	@Before
	fun setUp() {
		activity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()
		val activityBinding = mockk<ActivityEditorBinding>(relaxed = true)
		val contentBinding = ContentEditorBinding.inflate(activity.layoutInflater)
		ActivityEditorBinding::class.java
			.getDeclaredField("content")
			.apply { isAccessible = true }
			.set(activityBinding, contentBinding)
		activity._binding = activityBinding
		binding = activityBinding
		binding.content.tabs.addOnTabSelectedListener(activity)
	}

	private fun addFileTab(file: File): CodeEditorView {
		val editor = mockk<CodeEditorView>(relaxed = true)
		every { editor.parent } returns null
		every { editor.file } returns file
		every { editor.isModified } returns false

		activity.editorViewModel.addFile(file)
		binding.content.editorContainer.addView(editor)

		val tab =
			binding.content.tabs
				.newTab()
				.apply { text = file.name }
		binding.content.tabs.addTab(tab)
		return editor
	}

	@Suppress("UNCHECKED_CAST")
	private fun addPluginTab(pluginId: String) {
		val pluginIndicesField =
			EditorHandlerActivity::class.java.getDeclaredField("pluginTabIndices").apply {
				isAccessible = true
			}
		val tabIndexToPluginIdField =
			EditorHandlerActivity::class.java.getDeclaredField("tabIndexToPluginId").apply {
				isAccessible = true
			}

		val pluginIndices = pluginIndicesField.get(activity) as MutableMap<String, Int>
		val tabIndexToPluginId = tabIndexToPluginIdField.get(activity) as MutableMap<Int, String>

		val pos = binding.content.tabs.tabCount
		pluginIndices[pluginId] = pos
		tabIndexToPluginId[pos] = pluginId

		val dummyView = View(activity)
		binding.content.editorContainer.addView(dummyView)

		val tab =
			binding.content.tabs
				.newTab()
				.apply { text = pluginId }
		binding.content.tabs.addTab(tab)
	}

	@Test
	fun `closing active tab updates current file and displayed child to newly shown tab`() {
		addFileTab(fileA)
		addFileTab(fileB)

		// Select tab 1 (fileB)
		binding.content.tabs
			.getTabAt(1)!!
			.select()
		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileB)
		assertThat(binding.content.editorContainer.displayedChild).isEqualTo(1)

		// Close fileB (index 1)
		activity.closeFile(1)

		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileA)
		assertThat(activity.editorViewModel.getCurrentFileIndex()).isEqualTo(0)
		assertThat(binding.content.editorContainer.displayedChild).isEqualTo(0)
		assertThat(binding.content.tabs.selectedTabPosition).isEqualTo(0)
	}

	@Test
	fun `closeOthers after closing active tab keeps the shown tab`() {
		addFileTab(fileA)
		addFileTab(fileB)
		addFileTab(fileC)

		// Select tab 0 (fileA)
		binding.content.tabs
			.getTabAt(0)!!
			.select()
		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileA)

		// Close fileA. TabLayout selects tab 0 which is now fileB.
		activity.closeFile(0)

		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileB)
		assertThat(activity.editorViewModel.getCurrentFileIndex()).isEqualTo(0)
		assertThat(binding.content.editorContainer.displayedChild).isEqualTo(0)
		assertThat(binding.content.tabs.selectedTabPosition).isEqualTo(0)

		// Calling closeOthers should preserve the shown tab (fileB) and close fileC
		activity.closeOthers()

		assertThat(activity.editorViewModel.getOpenedFiles()).containsExactly(fileB)
		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileB)
	}

	@Test
	fun `closing a tab to the left of the active tab keeps the active tab and updates indices`() {
		addFileTab(fileA)
		addFileTab(fileB)
		addFileTab(fileC)

		// Select tab 2 (fileC)
		binding.content.tabs
			.getTabAt(2)!!
			.select()
		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileC)
		assertThat(binding.content.editorContainer.displayedChild).isEqualTo(2)

		// Close fileA (index 0)
		activity.closeFile(0)

		assertThat(activity.editorViewModel.getOpenedFiles()).containsExactly(fileB, fileC).inOrder()
		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileC)
		assertThat(activity.editorViewModel.getCurrentFileIndex()).isEqualTo(1)
		assertThat(binding.content.editorContainer.displayedChild).isEqualTo(1)
		assertThat(binding.content.tabs.selectedTabPosition).isEqualTo(1)
	}

	@Test
	fun `closing the last remaining tab clears current file and hides tabs`() {
		addFileTab(fileA)
		binding.content.tabs
			.getTabAt(0)!!
			.select()
		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileA)

		activity.closeFile(0)

		assertThat(activity.editorViewModel.getCurrentFile()).isNull()
		assertThat(activity.editorViewModel.getCurrentFileIndex()).isEqualTo(-1)
		assertThat(binding.content.tabs.tabCount).isEqualTo(0)
		assertThat(binding.content.tabs.visibility).isEqualTo(View.GONE)
		assertThat(binding.content.viewContainer.displayedChild).isEqualTo(1)
	}

	@Test
	fun `closing an active plugin tab selects neighbouring file tab without desync`() {
		addFileTab(fileA)
		addPluginTab("plugin.tools")

		// Select plugin tab at position 1
		binding.content.tabs
			.getTabAt(1)!!
			.select()
		assertThat(binding.content.editorContainer.displayedChild).isEqualTo(1)

		// Close current tab (the plugin tab)
		activity.closeCurrentFile()

		assertThat(binding.content.tabs.selectedTabPosition).isEqualTo(0)
		assertThat(binding.content.editorContainer.displayedChild).isEqualTo(0)
		assertThat(activity.editorViewModel.getCurrentFile()).isEqualTo(fileA)
		assertThat(activity.editorViewModel.getCurrentFileIndex()).isEqualTo(0)
	}
}
