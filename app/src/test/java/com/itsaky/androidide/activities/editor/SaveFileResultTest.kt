package com.itsaky.androidide.activities.editor

import android.os.Looper
import androidx.lifecycle.Observer
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.EditorProviderImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Covers the file-targeted save path's outcomes that need no live editor view. */
@RunWith(RobolectricTestRunner::class)
@Config(application = SaveFileResultTest.TestApp::class)
class SaveFileResultTest {
	open class TestApp : BaseApplication()

	@Test
	fun givenNoOpenEditorForTheFile_whenSaved_thenItFailsWithoutRaisingTheSavingFlag() {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()
		val mainLooper = shadowOf(Looper.getMainLooper())
		mainLooper.idle()

		// Every emission, not just the final value: raising the flag for a save with nothing to
		// do churns SaveFileAction's enabled state through invalidateOptionsMenu even though it
		// settles back where it started.
		val emissions = mutableListOf<Boolean>()
		val observer = Observer<Boolean> { emissions.add(it) }
		activity.editorViewModel._filesSaving.observeForever(observer)

		try {
			val result = pumpMainUntil(mainLooper) { activity.saveFileResult(tempFile()) }

			assertThat(result).isFalse()
			// Only observeForever's replay of the current value.
			assertThat(emissions).containsExactly(false)
		} finally {
			activity.editorViewModel._filesSaving.removeObserver(observer)
		}
	}

	@Test
	fun givenADetachedProvider_whenSaved_thenItReportsFailureRatherThanThrowing() {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()
		val provider = EditorProviderImpl(activity)

		// What the activity's onDestroy does; the weak activity reference is cleared with it.
		provider.dispose()

		// No suspension happens once the activity is gone, so blocking here cannot deadlock.
		assertThat(runBlocking { provider.saveFile(tempFile()) }).isFalse()
	}

	private fun tempFile() =
		File.createTempFile("save-file-result-", ".kt").apply {
			writeText("val answer = 42\n")
			deleteOnExit()
		}

	/**
	 * Runs [block] on a worker thread while draining the main looper, and returns its result.
	 *
	 * `saveFileResult` resumes through the main dispatcher, so `runBlocking` on Robolectric's
	 * main thread would park the very looper the resumption needs - the deadlock the plugin
	 * API documents. The worker keeps the main thread free to drain those hops.
	 */
	private fun pumpMainUntil(
		mainLooper: ShadowLooper,
		block: suspend () -> Boolean,
	): Boolean {
		val outcome = AtomicReference<Result<Boolean>?>(null)
		val worker =
			thread(isDaemon = true) {
				outcome.set(runCatching { runBlocking(Dispatchers.IO) { block() } })
			}
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		while (outcome.get() == null && System.currentTimeMillis() < deadline) {
			mainLooper.idle()
			Thread.sleep(1)
		}
		worker.join(TIMEOUT_MS)
		return checkNotNull(outcome.get()) { "save did not complete within ${TIMEOUT_MS}ms" }.getOrThrow()
	}

	private companion object {
		const val TIMEOUT_MS = 10_000L
	}
}
