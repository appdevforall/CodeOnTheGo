package com.itsaky.androidide.quickbuild

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the per-project namespacing of "has this project used Quick Build".
 *
 * The flag drives the first-run explanation. Un-namespaced, the explanation shows once ever
 * and every project after the first loses it; written under a bare suffix with no project
 * open, the next project opened inherits a stranger's answer. Both are silent - the store
 * still reads and writes without error - so only a test that opens two paths can see them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PreferencesQuickBuildHistoryStoreTest {
	private val context: Context = ApplicationProvider.getApplicationContext()

	private val prefs get() = context.getSharedPreferences("quick_build_mode", Context.MODE_PRIVATE)

	private fun storeFor(projectPath: String?) = PreferencesQuickBuildHistoryStore(context) { projectPath }

	@Test
	fun `a project reads back its own first use`() {
		val store = storeFor("/storage/projects/alpha")

		assertThat(store.hasUsedQuickBuild()).isFalse()
		store.setHasUsedQuickBuild(true)

		assertThat(store.hasUsedQuickBuild()).isTrue()
	}

	@Test
	fun `one project's first use does not answer for another - the key is namespaced by project path`() {
		storeFor("/storage/projects/alpha").setHasUsedQuickBuild(true)

		assertThat(storeFor("/storage/projects/alpha").hasUsedQuickBuild()).isTrue()
		assertThat(storeFor("/storage/projects/beta").hasUsedQuickBuild()).isFalse()
	}

	@Test
	fun `with no project open the write is dropped, not parked under a bare key the next project would read`() {
		storeFor(null).setHasUsedQuickBuild(true)
		storeFor("   ").setHasUsedQuickBuild(true)

		// The read being false is not enough: a value stored under a project-less key is
		// invisible here and still poisons whichever project is opened next.
		assertThat(prefs.all).isEmpty()
		assertThat(storeFor(null).hasUsedQuickBuild()).isFalse()
		assertThat(storeFor("   ").hasUsedQuickBuild()).isFalse()
	}
}
