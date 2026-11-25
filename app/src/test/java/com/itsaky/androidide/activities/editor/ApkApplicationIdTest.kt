package com.itsaky.androidide.activities.editor

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ApkApplicationIdTest {
	private val apk = File("/tmp/app-debug.apk")

	private fun managerThrowing(error: Throwable): PackageManager =
		mockk { every { getPackageArchiveInfo(any<String>(), any<Int>()) } throws error }

	@Test
	fun `returns the package name the archive declares`() {
		val info = PackageInfo().apply { packageName = "com.example.app" }
		val manager = mockk<PackageManager> { every { getPackageArchiveInfo(any<String>(), any<Int>()) } returns info }

		assertThat(ProjectHandlerActivity.apkApplicationId(manager, apk)).isEqualTo("com.example.app")
	}

	@Test
	fun `an Exception from the parse gives null`() {
		assertThat(ProjectHandlerActivity.apkApplicationId(managerThrowing(IllegalStateException("bad apk")), apk)).isNull()
	}

	@Test
	fun `a LinkageError from the parse gives null`() {
		assertThat(ProjectHandlerActivity.apkApplicationId(managerThrowing(NoClassDefFoundError("missing")), apk)).isNull()
	}

	@Test
	fun `an OutOfMemoryError from the parse propagates`() {
		val thrown =
			runCatching { ProjectHandlerActivity.apkApplicationId(managerThrowing(OutOfMemoryError("test")), apk) }
				.exceptionOrNull()

		assertThat(thrown).isInstanceOf(OutOfMemoryError::class.java)
	}
}
