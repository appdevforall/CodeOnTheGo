package com.itsaky.androidide.actions.file

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.activities.ExternalFileInstallActivity
import com.itsaky.androidide.utils.fileProviderAuthority
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class InstallFileActionTest {
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
}
