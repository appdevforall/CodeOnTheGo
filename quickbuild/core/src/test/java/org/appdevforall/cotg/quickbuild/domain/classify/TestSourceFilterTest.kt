package org.appdevforall.cotg.quickbuild.domain.classify

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The boundary between a save Quick Build ignores and one it must still build.
 *
 * The precision matters in both directions. Ignoring a source set that DOES ship in the variant
 * (`src/debug`, a flavor) would leave the running app missing an edit the user made, with no
 * warning; building a test source set costs a full Gradle build that cannot change the app at all.
 */
class TestSourceFilterTest {
	private fun split(
		modified: List<String> = emptyList(),
		removed: List<String> = emptyList(),
	): TestSourceFilter.Split =
		TestSourceFilter.split(
			ChangedFiles.Known(modified.map(::File).toSet(), removed.map(::File).toSet()),
		)

	@Test
	fun `a unit test save is dropped and leaves nothing to build`() {
		val split = split(modified = listOf("app/src/test/java/com/example/FooTest.kt"))

		assertThat(split.droppedTestSources).isTrue()
		assertThat(split.buildable.isEmpty).isTrue()
	}

	@Test
	fun `an instrumentation test save is dropped`() {
		val split = split(modified = listOf("app/src/androidTest/java/com/example/FooTest.kt"))

		assertThat(split.droppedTestSources).isTrue()
		assertThat(split.buildable.isEmpty).isTrue()
	}

	@Test
	fun `testFixtures is dropped - it ships to consumers' tests, not into the app`() {
		val split = split(modified = listOf("app/src/testFixtures/java/com/example/Fixtures.kt"))

		assertThat(split.droppedTestSources).isTrue()
		assertThat(split.buildable.isEmpty).isTrue()
	}

	@Test
	fun `a flavor-qualified test source set is dropped`() {
		// AGP appends the flavor and build type: src/testProDebug, src/androidTestDebug.
		val split =
			split(
				modified =
					listOf(
						"app/src/testProDebug/java/com/example/FooTest.kt",
						"app/src/androidTestDebug/java/com/example/BarTest.kt",
					),
			)

		assertThat(split.droppedTestSources).isTrue()
		assertThat(split.buildable.isEmpty).isTrue()
	}

	@Test
	fun `a test resource or asset is dropped too, not just test code`() {
		val split =
			split(
				modified =
					listOf(
						"app/src/test/resources/fixture.json",
						"app/src/androidTest/assets/sample.png",
					),
			)

		assertThat(split.droppedTestSources).isTrue()
		assertThat(split.buildable.isEmpty).isTrue()
	}

	@Test
	fun `a deleted test file is dropped as well - removing a test deploys no more than saving one`() {
		val split = split(removed = listOf("app/src/test/java/com/example/FooTest.kt"))

		assertThat(split.droppedTestSources).isTrue()
		assertThat(split.buildable.isEmpty).isTrue()
	}

	@Test
	fun `src debug ships in the variant, so it is kept`() {
		// The precision this class exists for: a debug source set IS compiled into the app the
		// user runs. Ignoring it would leave the running app missing their edit, silently.
		val debugSource = "app/src/debug/java/com/example/Debug.kt"

		val split = split(modified = listOf(debugSource))

		assertThat(split.droppedTestSources).isFalse()
		assertThat(split.buildable.files).containsExactly(File(debugSource))
	}

	@Test
	fun `a flavor source set is kept`() {
		val flavorSource = "app/src/pro/java/com/example/Pro.kt"

		val split = split(modified = listOf(flavorSource))

		assertThat(split.droppedTestSources).isFalse()
		assertThat(split.buildable.files).containsExactly(File(flavorSource))
	}

	@Test
	fun `a flavor whose name merely begins with test is kept`() {
		// "testflavor" is a shipping source set. A bare startsWith("test") would ignore every
		// save in it, and the user would never be told why their edit did not appear.
		val flavorSource = "app/src/testflavor/java/com/example/Thing.kt"

		val split = split(modified = listOf(flavorSource))

		assertThat(split.droppedTestSources).isFalse()
		assertThat(split.buildable.files).containsExactly(File(flavorSource))
	}

	@Test
	fun `a package named test in main is kept`() {
		// The source set is the innermost src child, so a package called `test` cannot rename
		// the source set the file is really in.
		val mainSource = "app/src/main/java/com/example/test/Helper.kt"

		val split = split(modified = listOf(mainSource))

		assertThat(split.droppedTestSources).isFalse()
		assertThat(split.buildable.files).containsExactly(File(mainSource))
	}

	@Test
	fun `a main save beside a test save still builds, and the drop is still reported`() {
		val mainSource = "app/src/main/java/com/example/Foo.kt"

		val split =
			split(modified = listOf(mainSource, "app/src/test/java/com/example/FooTest.kt"))

		// The main edit must reach the build - a save-all writes both at once, and dropping the
		// whole batch would silently strand the edit the user can actually see.
		assertThat(split.buildable.files).containsExactly(File(mainSource))
		// And the notice is still owed: the test half did not deploy either.
		assertThat(split.droppedTestSources).isTrue()
	}

	@Test
	fun `a batch with no test source is passed through untouched`() {
		val mainSource = "app/src/main/java/com/example/Foo.kt"
		val removedSource = "app/src/main/java/com/example/Bar.kt"

		val split = split(modified = listOf(mainSource), removed = listOf(removedSource))

		assertThat(split.droppedTestSources).isFalse()
		assertThat(split.buildable.files).containsExactly(File(mainSource))
		assertThat(split.buildable.removed).containsExactly(File(removedSource))
	}

	@Test
	fun `a test source in a library module is dropped as well`() {
		// The module makes no difference: nothing in a test source set anywhere reaches the
		// app Quick Build deploys.
		val split = split(modified = listOf("lib/src/test/java/com/example/LibTest.kt"))

		assertThat(split.droppedTestSources).isTrue()
		assertThat(split.buildable.isEmpty).isTrue()
	}

	@Test
	fun `a path with no source set at all is kept`() {
		val gradleFile = "app/build.gradle.kts"

		val split = split(modified = listOf(gradleFile))

		assertThat(split.droppedTestSources).isFalse()
		assertThat(split.buildable.files).containsExactly(File(gradleFile))
	}
}
