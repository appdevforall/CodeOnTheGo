package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.junit.jupiter.api.Test
import java.io.File

/**
 * One test per edit class from the plan's section 1.2 matrix, plus the precedence and
 * honesty-fallback rules.
 */
class ChangeClassifierTest {
	private val classifier = ChangeClassifier()

	private fun classify(vararg paths: String): BuildRoute = classifier.classify(ChangedFiles.Known(paths.map(::File).toSet()))

	@Test
	fun `kotlin source is code only`() {
		assertThat(classify("app/src/main/java/com/example/Main.kt"))
			.isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `java source is code only`() {
		assertThat(classify("app/src/main/java/com/example/Main.java"))
			.isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `resource value file is resources only`() {
		assertThat(classify("app/src/main/res/values/strings.xml"))
			.isEqualTo(BuildRoute.ResourcesOnly)
	}

	@Test
	fun `layout and drawable files are resources only`() {
		assertThat(
			classify(
				"app/src/main/res/layout/activity_main.xml",
				"app/src/main/res/drawable/icon.png",
			),
		).isEqualTo(BuildRoute.ResourcesOnly)
	}

	@Test
	fun `asset file is assets only`() {
		assertThat(classify("app/src/main/assets/data/levels.json"))
			.isEqualTo(BuildRoute.AssetsOnly)
	}

	@Test
	fun `mixed kotlin and resource save compiles AND relinks`() {
		assertThat(
			classify(
				"app/src/main/java/com/example/Main.kt",
				"app/src/main/res/values/strings.xml",
			),
		).isEqualTo(BuildRoute.CodeAndResources)
	}

	@Test
	fun `code with assets classifies as code only`() {
		// Assets ride along in the deploy payload regardless; compile is the driver.
		assertThat(
			classify(
				"app/src/main/java/com/example/Main.kt",
				"app/src/main/assets/data/levels.json",
			),
		).isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `manifest change invalidates the session`() {
		assertThat(classify("app/src/main/AndroidManifest.xml"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.MANIFEST_CHANGED))
	}

	@Test
	fun `gradle build file invalidates the session`() {
		assertThat(classify("app/build.gradle.kts"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.GRADLE_CONFIG_CHANGED))
		assertThat(classify("settings.gradle"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.GRADLE_CONFIG_CHANGED))
		assertThat(classify("gradle.properties"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.GRADLE_CONFIG_CHANGED))
	}

	@Test
	fun `version catalog and wrapper properties invalidate the session`() {
		assertThat(classify("gradle/libs.versions.toml"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.GRADLE_CONFIG_CHANGED))
		assertThat(classify("gradle/wrapper/gradle-wrapper.properties"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.GRADLE_CONFIG_CHANGED))
	}

	@Test
	fun `invalidation wins over any accompanying code change`() {
		assertThat(
			classify(
				"app/src/main/java/com/example/Main.kt",
				"app/src/main/AndroidManifest.xml",
			),
		).isInstanceOf(BuildRoute.FullGradleBuild::class.java)
	}

	@Test
	fun `unsupported file under src falls back honestly`() {
		// A java-resource the quick path can't package: serving a quick build would be
		// stale, so it must route to Gradle.
		assertThat(classify("app/src/main/resources/config.properties"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED))
	}

	@Test
	fun `native library under jniLibs falls back honestly`() {
		// The quick path has no relink/redeploy story for a changed .so - serving a build that
		// still has the OLD native library loaded would be silently stale, so this must route
		// to Gradle like any other unsupported-file change (D7: native app corpus entry).
		assertThat(classify("app/src/main/jniLibs/arm64-v8a/libnativestub.so"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED))
	}

	@Test
	fun `unknown changed-set forces a full quick recompile, not a Gradle fallback`() {
		assertThat(classifier.classify(ChangedFiles.Unknown))
			.isEqualTo(BuildRoute.CodeAndResources)
	}

	@Test
	fun `empty known set is a no-op`() {
		assertThat(classifier.classify(ChangedFiles.Known.EMPTY)).isEqualTo(BuildRoute.NoOp)
	}

	@Test
	fun `annotation impact escalates a code change to a Gradle rebaseline`() {
		assertThat(
			classifierWith(active = true, escalates = true)
				.classify(ChangedFiles.Known(setOf(File("app/src/main/java/Dao.kt")))),
		).isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED))
	}

	@Test
	fun `annotation impact leaves a safe code change on the fast path`() {
		assertThat(
			classifierWith(active = true, escalates = false)
				.classify(ChangedFiles.Known(setOf(File("app/src/main/java/Ui.kt")))),
		).isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `annotation impact is never consulted for a resource-only change`() {
		var consulted = false
		val impact =
			object : AnnotationImpact {
				override val active = true

				override fun escalation(changedCodeFiles: List<File>): String {
					consulted = true
					return "should not be reached"
				}
			}

		assertThat(
			ChangeClassifier(impact)
				.classify(ChangedFiles.Known(setOf(File("app/src/main/res/values/strings.xml")))),
		).isEqualTo(BuildRoute.ResourcesOnly)
		assertThat(consulted).isFalse()
	}

	@Test
	fun `an unknown changed-set falls back to Gradle when processors are configured`() {
		// Cannot enumerate what changed, so cannot prove it missed processor input.
		assertThat(classifierWith(active = true, escalates = false).classify(ChangedFiles.Unknown))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED))
	}

	private fun classifierWith(
		active: Boolean,
		escalates: Boolean,
	): ChangeClassifier =
		ChangeClassifier(
			object : AnnotationImpact {
				override val active = active

				override fun escalation(changedCodeFiles: List<File>): String? = "annotation input changed".takeIf { escalates }
			},
		)
}
