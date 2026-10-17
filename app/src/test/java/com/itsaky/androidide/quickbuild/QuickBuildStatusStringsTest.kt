package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [QuickBuildStatusBar] keeps a deploy failure apart from a build failure by resource id, which a
 * test on ids cannot tell from two ids that carry the same text. A deploy failure means the code
 * compiled and only the delivery to the app failed, so its text must not send the user looking
 * for a compile error.
 */
@RunWith(RobolectricTestRunner::class)
class QuickBuildStatusStringsTest {
	@Test
	fun `a deploy failure reads differently from a build failure`() {
		val resources = RuntimeEnvironment.getApplication().resources

		assertThat(resources.getString(R.string.quick_build_status_deploy_failed))
			.isNotEqualTo(resources.getString(R.string.quick_build_status_failed))
	}
}
