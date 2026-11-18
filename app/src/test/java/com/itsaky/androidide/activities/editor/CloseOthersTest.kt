package com.itsaky.androidide.activities.editor

import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.widget.ViewFlipper
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.databinding.ActivityEditorBinding
import com.itsaky.androidide.databinding.ContentEditorBinding
import com.itsaky.androidide.ui.CodeEditorView
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * "Close others" keeps the shown file (ADFA-7141). The current file is set from the opened file
 * as soon as a tab is shown, while its editor's own file stays null until the text has loaded, or
 * for good if the read failed. Comparing against the editor's file closed the shown tab instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = CloseOthersTest.TestApp::class)
class CloseOthersTest {
	open class TestApp : BaseApplication()

	private val shown = File("/project/Shown.kt")
	private val a = File("/project/A.kt")
	private val b = File("/project/B.kt")

	@Test
	fun closeOthersKeepsAShownFileWhoseEditorHasNotLoaded() {
		val activity = activityWithTabs(listOf(shown to null, a to a, b to b))
		activity.editorViewModel.selectFile(0)

		activity.closeOthers()

		assertThat(activity.editorViewModel.getOpenedFiles()).containsExactly(shown)
	}

	@Test
	fun closeOthersKeepsALoadedShownFileInTheMiddle() {
		val activity = activityWithTabs(listOf(a to a, shown to shown, b to b))
		activity.editorViewModel.selectFile(1, shown)

		activity.closeOthers()

		assertThat(activity.editorViewModel.getOpenedFiles()).containsExactly(shown)
	}

	// A's editor has unsaved changes but no file yet, so the check before the loop misses it, and
	// closing it stops at the unsaved-changes dialog without closing anything.
	@Test
	fun closeOthersStopsWhenACloseAsksAboutUnsavedChanges() {
		var checks = 0
		val activity =
			activityWithTabs(listOf(shown to shown, a to null, b to b), modifiedAt = 1) {
				check(++checks <= 20) { "closeOthers kept trying to close the same tab" }
			}
		activity.editorViewModel.selectFile(0, shown)

		activity.closeOthers()

		assertThat(checks).isEqualTo(1)
		assertThat(activity.editorViewModel.getOpenedFiles()).containsExactly(shown, a, b).inOrder()
	}

	@Test
	fun closeOthersCarriesOnOnceTheUnsavedChangesAreDiscarded() {
		val activity = activityWithTabs(listOf(shown to shown, a to null, b to b), modifiedAt = 1)
		activity.editorViewModel.selectFile(0, shown)
		activity.closeOthers()

		// "No": discard the changes and close. The dialog delivers the click through the main looper.
		(ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
		shadowOf(Looper.getMainLooper()).idle()

		assertThat(activity.editorViewModel.getOpenedFiles()).containsExactly(shown)
	}

	/**
	 * An editor activity, built but not created as in the other activity tests here, whose tab
	 * strip and editor container follow its opened files. Each pair is an opened file and the file
	 * its editor reports: null while the editor loads. The editor at [modifiedAt], if any, has
	 * unsaved changes until it is marked saved, and [onModifiedCheck] runs each time that is asked.
	 */
	private fun activityWithTabs(
		tabs: List<Pair<File, File?>>,
		modifiedAt: Int = -1,
		onModifiedCheck: () -> Unit = {},
	): EditorHandlerActivity {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java, Intent()).get()
		var saved = false
		val editors =
			tabs
				.mapIndexed { i, (_, editorFile) ->
					mockk<CodeEditorView>(relaxed = true) {
						every { file } returns editorFile
						every { isModified } answers {
							if (i == modifiedAt) onModifiedCheck()
							i == modifiedAt && !saved
						}
						every { markAsSaved() } answers { if (i == modifiedAt) saved = true }
					}
				}.toMutableList()
		val tabStrip = mockk<TabLayout>(relaxed = true) { every { tabCount } answers { editors.size } }
		val container =
			mockk<ViewFlipper>(relaxed = true) {
				every { childCount } answers { editors.size }
				every { getChildAt(any()) } answers { editors.getOrNull(firstArg()) }
				every { removeViewAt(any()) } answers { editors.removeAt(firstArg<Int>()) }
			}
		// The binding's views are public Java fields, which mockk cannot stub.
		val content = mockk<ContentEditorBinding>(relaxed = true)
		ContentEditorBinding::class.java
			.getDeclaredField("tabs")
			.apply { isAccessible = true }
			.set(content, tabStrip)
		ContentEditorBinding::class.java
			.getDeclaredField("editorContainer")
			.apply { isAccessible = true }
			.set(content, container)
		val binding = mockk<ActivityEditorBinding>(relaxed = true)
		ActivityEditorBinding::class.java
			.getDeclaredField("content")
			.apply { isAccessible = true }
			.set(binding, content)
		activity._binding = binding

		tabs.forEach { (opened, _) -> activity.editorViewModel.addFile(opened) }
		return activity
	}
}
