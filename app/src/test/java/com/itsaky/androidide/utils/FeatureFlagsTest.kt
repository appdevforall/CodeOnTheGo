package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private const val EXPERIMENTS_FILE_NAME = "CodeOnTheGo.exp"

/**
 * [FeatureFlags] reads sentinel files from the public Downloads directory, which only
 * resolves under Robolectric (the plain android.jar stub throws), so this lives in `:app`
 * next to the other Robolectric tests rather than in `:common`.
 *
 * The scenario worth guarding is the two-phase startup in
 * [com.itsaky.androidide.app.IDEApplication]: the device-protected phase reads the flags and
 * may run in direct boot mode, where external storage is not mounted and every flag reads as
 * absent. That snapshot is indistinguishable from a genuine "device has no flag files", so
 * the credential-protected phase must re-read rather than trust it.
 */
@RunWith(RobolectricTestRunner::class)
class FeatureFlagsTest {
	/**
	 * The directory [FeatureFlags] itself resolved, read back rather than recomputed:
	 * the object captures it once at class-init, while Robolectric hands out a fresh
	 * external-storage root per test method - recomputing it makes every test after the
	 * first write its sentinel files somewhere the object is not looking.
	 */
	private val downloadsDir: File
		get() =
			FeatureFlags::class.java
				.getDeclaredField("downloadsDir")
				.apply { isAccessible = true }
				.get(FeatureFlags) as File

	private val experimentsFile: File
		get() = File(downloadsDir, EXPERIMENTS_FILE_NAME)

	@Before
	fun reset() {
		downloadsDir.mkdirs()
		experimentsFile.delete()
		// FeatureFlags is a process singleton; clear the cache so each test starts from
		// "nothing has been read yet". Reflection because there is deliberately no
		// production reset hook (same reason the androidTest helper uses it).
		setPrivate("flags", flagsDefault())
		setPrivate("loaded", false)
	}

	@Test
	fun `initialize reads a present flag file`() {
		experimentsFile.writeText("")

		runBlocking { FeatureFlags.initialize() }

		assertThat(FeatureFlags.isExperimentsEnabled).isTrue()
	}

	@Test
	fun `initialize reads an absent flag file as off`() {
		runBlocking { FeatureFlags.initialize() }

		assertThat(FeatureFlags.isExperimentsEnabled).isFalse()
	}

	@Test
	fun `initialize is one-shot - a second call does not touch disk`() {
		runBlocking { FeatureFlags.initialize() }
		experimentsFile.writeText("")

		runBlocking { FeatureFlags.initialize() }

		assertThat(FeatureFlags.isExperimentsEnabled).isFalse()
	}

	@Test
	fun `refresh re-reads after a startup snapshot that could not see the flag files`() {
		// Direct boot: external storage is not mounted, so every flag reads as absent.
		runBlocking { FeatureFlags.initialize() }
		assertThat(FeatureFlags.isExperimentsEnabled).isFalse()

		// The user unlocks; the flag file is now visible. The credential-protected phase
		// re-reads instead of relying on initialize() being a no-op by then.
		experimentsFile.writeText("")
		runBlocking { FeatureFlags.refresh() }

		assertThat(FeatureFlags.isExperimentsEnabled).isTrue()
	}

	@Test
	fun `refresh picks up a flag file that has been deleted`() {
		experimentsFile.writeText("")
		runBlocking { FeatureFlags.initialize() }
		assertThat(FeatureFlags.isExperimentsEnabled).isTrue()

		experimentsFile.delete()
		runBlocking { FeatureFlags.refresh() }

		assertThat(FeatureFlags.isExperimentsEnabled).isFalse()
	}

	private fun setPrivate(
		name: String,
		value: Any?,
	) {
		FeatureFlags::class.java
			.getDeclaredField(name)
			.apply { isAccessible = true }
			.set(FeatureFlags, value)
	}

	private fun flagsDefault(): Any =
		checkNotNull(
			Class
				.forName("com.itsaky.androidide.utils.FlagsCache")
				.getDeclaredField("DEFAULT")
				.apply { isAccessible = true }
				.get(null),
		)
}
