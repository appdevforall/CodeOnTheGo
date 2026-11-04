package org.appdevforall.cotg.quickbuild.domain.watch

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Covers the watcher-batch reconciliation decision table directly. The
 * QuickBuildSessionManager tests remain the end-to-end regression harness for the same
 * behavior.
 */
class WatcherBatchReconcilerTest {
	private val source = File("/project/app/src/main/java/com/example/Main.kt")
	private val resource = File("/project/app/src/main/res/layout/activity_main.xml")
	private val temp = File("/project/app/src/main/java/com/example/sedAbC123")
	private val javaResource = File("/project/app/src/main/resources/config.properties")
	private val nativeLib = File("/project/app/src/main/jniLibs/arm64-v8a/libnativestub.so")

	private fun reconcile(
		batch: ChangedFiles.Known,
		existing: Set<File>,
	): ChangedFiles.Known = WatcherBatchReconciler.reconcile(batch) { it in existing }

	@Test
	fun `a deleted java resource is kept as a removal, not dropped as noise`() {
		// Modifying this file routes to FullGradleBuild because the quick path cannot package
		// it; dropping its DELETION left the proxy app serving the deleted content forever.
		val result = reconcile(ChangedFiles.Known(files = emptySet(), removed = setOf(javaResource)), existing = emptySet())

		assertThat(result.removed).containsExactly(javaResource)
	}

	@Test
	fun `a deleted native library is kept as a removal`() {
		val result = reconcile(ChangedFiles.Known(files = emptySet(), removed = setOf(nativeLib)), existing = emptySet())

		assertThat(result.removed).containsExactly(nativeLib)
	}

	@Test
	fun `a vanished modified java resource becomes a removal rather than noise`() {
		val result = reconcile(ChangedFiles.Known(setOf(javaResource)), existing = emptySet())

		assertThat(result.files).isEmpty()
		assertThat(result.removed).containsExactly(javaResource)
	}

	@Test
	fun `an extensionless temp is still dropped - the negative control for the packaged-file rule`() {
		val result = reconcile(ChangedFiles.Known(files = emptySet(), removed = setOf(temp)), existing = emptySet())

		assertThat(result.isEmpty).isTrue()
	}

	@Test
	fun `a modified file that still exists stays modified`() {
		val result = reconcile(ChangedFiles.Known(setOf(source)), existing = setOf(source))

		assertThat(result.files).containsExactly(source)
		assertThat(result.removed).isEmpty()
	}

	@Test
	fun `a vanished modified file with a recognized shape becomes a removal`() {
		val result = reconcile(ChangedFiles.Known(setOf(source)), existing = emptySet())

		assertThat(result.files).isEmpty()
		assertThat(result.removed).containsExactly(source)
	}

	@Test
	fun `a vanished modified file with no recognized shape is dropped as noise`() {
		val result = reconcile(ChangedFiles.Known(setOf(temp)), existing = emptySet())

		assertThat(result.isEmpty).isTrue()
	}

	@Test
	fun `a watcher-reported removal with a recognized shape is kept`() {
		val result =
			reconcile(
				ChangedFiles.Known(emptySet(), removed = setOf(source)),
				existing = emptySet(),
			)

		assertThat(result.files).isEmpty()
		assertThat(result.removed).containsExactly(source)
	}

	@Test
	fun `a watcher-reported removal with no recognized shape is dropped`() {
		val result =
			reconcile(
				ChangedFiles.Known(emptySet(), removed = setOf(temp)),
				existing = emptySet(),
			)

		assertThat(result.isEmpty).isTrue()
	}

	@Test
	fun `a mixed batch reconciles each path independently`() {
		val vanishedResource = File("/project/app/src/main/res/values/strings.xml")
		val result =
			reconcile(
				ChangedFiles.Known(
					setOf(source, vanishedResource, temp),
					removed = setOf(resource),
				),
				existing = setOf(source),
			)

		assertThat(result.files).containsExactly(source)
		assertThat(result.removed).containsExactly(vanishedResource, resource)
	}

	@Test
	fun `a persisting file with no recognized shape stays modified for the honest fallback`() {
		val javaResource = File("/project/app/src/main/java/com/example/config.properties")
		val result =
			reconcile(ChangedFiles.Known(setOf(javaResource)), existing = setOf(javaResource))

		assertThat(result.files).containsExactly(javaResource)
		assertThat(result.removed).isEmpty()
	}
}
