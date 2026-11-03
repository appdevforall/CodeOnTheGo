package com.itsaky.androidide.actions.file

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.ExternalFileInstallActivity
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.utils.fileProviderAuthority
import com.itsaky.androidide.utils.fileProviderUriFor
import com.itsaky.androidide.utils.flashError
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class InstallFileActionTest {
	private val activity = mockk<EditorHandlerActivity>(relaxed = true)
	private val data = ActionData.create(activity)
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val uri = Uri.parse("content://test.providers.fileprovider/a.cgt")

	@After
	fun tearDown() {
		unmockkStatic(FILE_PROVIDER_UTILS, FLASHBAR_UTILS)
	}

	private fun currentFile(file: File?) {
		every { activity.editorViewModel.getOpenedFiles() } returns listOfNotNull(file)
		every { activity.editorViewModel.getCurrentFile() } returns file
	}

	@Test
	fun `cgt maps to TEMPLATE in any case`() {
		assertThat(installKindFor(File("a.cgt"))).isEqualTo(InstallKind.TEMPLATE)
		assertThat(installKindFor(File("a.CGT"))).isEqualTo(InstallKind.TEMPLATE)
	}

	@Test
	fun `cgp and apk keep their kinds`() {
		assertThat(installKindFor(File("a.cgp"))).isEqualTo(InstallKind.PLUGIN)
		assertThat(installKindFor(File("a.Cgp"))).isEqualTo(InstallKind.PLUGIN)
		assertThat(installKindFor(File("a.apk"))).isEqualTo(InstallKind.APK)
		assertThat(installKindFor(File("a.APK"))).isEqualTo(InstallKind.APK)
	}

	@Test
	fun `other extensions, no extension and no file are not installable`() {
		assertThat(installKindFor(File("a.zip"))).isNull()
		assertThat(installKindFor(File("a.txt"))).isNull()
		assertThat(installKindFor(File("cgt"))).isNull()
		assertThat(installKindFor(File("a.cgt.bak"))).isNull()
		assertThat(installKindFor(null)).isNull()
	}

	@Test
	fun `template install intent targets the install activity with a FileProvider uri and read grant`() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val file = File(context.filesDir, "my.cgt").apply { writeText("x") }

		val intent = templateInstallIntent(context, file)

		assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
		assertThat(intent.component?.className).isEqualTo(ExternalFileInstallActivity::class.java.name)
		assertThat(intent.data?.scheme).isEqualTo("content")
		assertThat(intent.data?.authority).isEqualTo(context.fileProviderAuthority())
		assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
			.isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	}

	@Test
	fun `Install is offered for a cgt tab in any case and hidden for other files`() {
		for ((name, visible) in listOf("a.cgt" to true, "a.CGT" to true, "a.cgp" to true, "a.apk" to true, "a.txt" to false)) {
			currentFile(File(name))

			val action = InstallFileAction(context, 0).apply { prepare(data) }

			assertThat(action.visible).isEqualTo(visible)
			assertThat(action.enabled).isEqualTo(visible)
		}
	}

	@Test
	fun `tapping Install on a cgt starts the install activity with the file's FileProvider uri`() {
		mockkStatic(FILE_PROVIDER_UTILS)
		every { any<Context>().fileProviderUriFor(any()) } returns uri
		currentFile(File("a.cgt"))

		val result = runBlocking { InstallFileAction(context, 0).execAction(data) }

		val started = slot<Intent>()
		verify(exactly = 1) { activity.startActivity(capture(started)) }
		assertThat(result).isEqualTo(true)
		assertThat(started.captured.component?.className).isEqualTo(ExternalFileInstallActivity::class.java.name)
		assertThat(started.captured.data).isEqualTo(uri)
	}

	@Test
	fun `a failed launch flashes the error and reports failure instead of crashing`() {
		mockkStatic(FILE_PROVIDER_UTILS, FLASHBAR_UTILS)
		every { any<Context>().fileProviderUriFor(any()) } returns uri
		every { any<Activity>().flashError(any<String>()) } just runs
		every { activity.startActivity(any<Intent>()) } throws ActivityNotFoundException()
		currentFile(File("a.cgt"))

		val result = runBlocking { InstallFileAction(context, 0).execAction(data) }

		assertThat(result).isEqualTo(false)
		verify(exactly = 1) { activity.flashError(any<String>()) }
	}

	private companion object {
		const val FILE_PROVIDER_UTILS = "com.itsaky.androidide.utils.FileProviderUtilsKt"
		const val FLASHBAR_UTILS = "com.itsaky.androidide.utils.FlashbarActivityUtilsKt"
	}
}
