package com.itsaky.androidide.quickbuild

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bench trampoline (ADFA-4128) opens a project and starts a Gradle build on request, and it
 * is exported - it has to be, since adb shell holds no START_ANY_ACTIVITY and could not reach a
 * non-exported activity with `am start`. Its feature flags are NOT a security gate: they are
 * files in the public Downloads directory that any app with storage access can create, which
 * left "start a Gradle build in CoGo" callable by any installed app.
 *
 * So the reachability gate is a permission adb shell holds and a third-party app cannot get.
 * Asserted against the merged manifest, because the gate is one attribute and its absence is
 * invisible in the code.
 */
@RunWith(RobolectricTestRunner::class)
class QuickBuildBenchActivityGateTest {
	@Test
	fun `the bench activity is reachable only by a caller holding a permission no app can get`() {
		val context = ApplicationProvider.getApplicationContext<Context>()

		val info =
			context.packageManager.getActivityInfo(
				ComponentName(context, QuickBuildBenchActivity::class.java),
				0,
			)

		// Held by com.android.shell (uid 2000) and bypassed by root, so `am start` from adb
		// still works; signature|privileged|development, so no third-party app can hold it.
		assertThat(info.permission).isEqualTo("android.permission.DUMP")
		// Documents the other half of the pair: dropping the export would break the harness,
		// which is why the permission - not un-exporting - is the fix.
		assertThat(info.exported).isTrue()
	}
}
