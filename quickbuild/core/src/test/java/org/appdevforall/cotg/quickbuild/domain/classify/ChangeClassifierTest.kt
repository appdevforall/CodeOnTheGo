package org.appdevforall.cotg.quickbuild.domain.classify

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.junit.jupiter.api.Test
import java.io.File

/**
 * One test per edit class [ChangeClassifier] routes, plus the precedence and
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
		// to Gradle like any other unsupported-file change (a native app's .c/.h sources).
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
	fun `annotation impact leaves a safe code change on the live reload path`() {
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

	@Test
	fun `hasRecognizedShape is true for every classifiable kind and false for unsupported`() {
		assertThat(ChangeClassifier.hasRecognizedShape(File("app/src/main/java/com/example/Main.kt")))
			.isTrue()
		assertThat(ChangeClassifier.hasRecognizedShape(File("app/src/main/java/com/example/Main.java")))
			.isTrue()
		assertThat(ChangeClassifier.hasRecognizedShape(File("app/src/main/res/values/strings.xml")))
			.isTrue()
		assertThat(ChangeClassifier.hasRecognizedShape(File("app/src/main/assets/data/levels.json")))
			.isTrue()
		assertThat(ChangeClassifier.hasRecognizedShape(File("app/build.gradle.kts"))).isTrue()
		assertThat(ChangeClassifier.hasRecognizedShape(File("app/src/main/AndroidManifest.xml")))
			.isTrue()
		// The sibling temp an atomic-rename save leaves behind: no dot-prefix or known
		// suffix, no extension at all - exactly the shape WatchFilter can't name-filter.
		assertThat(ChangeClassifier.hasRecognizedShape(File("app/src/main/java/com/example/sedAbC123")))
			.isFalse()
		assertThat(ChangeClassifier.hasRecognizedShape(File("app/src/main/resources/config.properties")))
			.isFalse()
	}

	private fun classifyRemoved(vararg paths: String): BuildRoute =
		classifier.classify(ChangedFiles.Known(emptySet(), paths.map(::File).toSet()))

	@Test
	fun `a removed kotlin source is code only`() {
		assertThat(classifyRemoved("app/src/main/java/com/example/Main.kt"))
			.isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `a removed resource is resources only`() {
		assertThat(classifyRemoved("app/src/main/res/values/strings.xml"))
			.isEqualTo(BuildRoute.ResourcesOnly)
	}

	@Test
	fun `a removed gradle file is a full gradle build`() {
		assertThat(classifyRemoved("app/build.gradle.kts"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.GRADLE_CONFIG_CHANGED))
	}

	@Test
	fun `a removed manifest is a full gradle build`() {
		assertThat(classifyRemoved("app/src/main/AndroidManifest.xml"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.MANIFEST_CHANGED))
	}

	@Test
	fun `a modified source plus a removed source is one code build`() {
		assertThat(
			classifier.classify(
				ChangedFiles.Known(
					files = setOf(File("app/src/main/java/com/example/A.kt")),
					removed = setOf(File("app/src/main/java/com/example/B.kt")),
				),
			),
		).isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `an empty known set with no removals is a no-op`() {
		assertThat(classifier.classify(ChangedFiles.Known.EMPTY)).isEqualTo(BuildRoute.NoOp)
	}

	// Multi-module boundary (Level 1): a live reload builds only the app module.

	private val moduleAware = ChangeClassifier(fastPathRoots = listOf(File("app/src")))

	private fun classifyScoped(vararg paths: String): BuildRoute = moduleAware.classify(ChangedFiles.Known(paths.map(::File).toSet()))

	@Test
	fun `app-module code inside the live-reload scope stays a code build`() {
		assertThat(classifyScoped("app/src/main/java/com/example/A.kt"))
			.isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `library-module code outside the scope rebaselines`() {
		assertThat(classifyScoped("feature-login/src/main/java/com/example/Login.kt"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED))
	}

	@Test
	fun `library-module resource outside the scope rebaselines`() {
		assertThat(classifyScoped("core-ui/src/main/res/values/colors.xml"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED))
	}

	@Test
	fun `library-module asset outside the scope rebaselines`() {
		assertThat(classifyScoped("data/src/main/assets/seed.json"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED))
	}

	@Test
	fun `an app edit beside a library edit rebaselines - never live-reload a partial changeset`() {
		assertThat(
			classifyScoped(
				"app/src/main/java/com/example/A.kt",
				"feature-login/src/main/java/com/example/Login.kt",
			),
		).isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED))
	}

	@Test
	fun `a removed library-module source rebaselines`() {
		assertThat(
			moduleAware.classify(
				ChangedFiles.Known(files = emptySet(), removed = setOf(File("feature-login/src/main/java/com/example/Gone.kt"))),
			),
		).isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED))
	}

	@Test
	fun `empty live-reload roots disables the boundary - single-module behavior is unchanged`() {
		// The default classifier (no fastPathRoots) must treat any src code as a code build,
		// preserving pre-multi-module semantics for single-module projects and shape tests.
		assertThat(classify("feature-login/src/main/java/com/example/Login.kt"))
			.isEqualTo(BuildRoute.CodeOnly)
	}

	// Assets below API 30: nothing on the device serves a deployed asset payload.

	private val noAssetServing = ChangeClassifier(assetsLiveReloadable = false)

	private fun classifyUnservedAssets(vararg paths: String): BuildRoute =
		noAssetServing.classify(ChangedFiles.Known(paths.map(::File).toSet()))

	@Test
	fun `an asset edit rebaselines when the device cannot serve deployed assets`() {
		assertThat(classifyUnservedAssets("app/src/main/assets/data/levels.json"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED))
	}

	@Test
	fun `code beside an asset rebaselines too - the asset rides the code payload`() {
		assertThat(
			classifyUnservedAssets(
				"app/src/main/java/com/example/Main.kt",
				"app/src/main/assets/data/levels.json",
			),
		).isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED))
	}

	@Test
	fun `a removed asset rebaselines when the device cannot serve deployed assets`() {
		assertThat(
			noAssetServing.classify(
				ChangedFiles.Known(emptySet(), setOf(File("app/src/main/assets/data/levels.json"))),
			),
		).isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED))
	}

	@Test
	fun `the gate is assets-only - resources keep their own legacy path`() {
		// API 28/29 resources DO have a swap mechanism (LegacyResourceSwap), so gating them
		// here would send every strings-xml edit to Gradle for nothing.
		assertThat(classifyUnservedAssets("app/src/main/res/values/strings.xml"))
			.isEqualTo(BuildRoute.ResourcesOnly)
	}

	@Test
	fun `code with no asset stays on the live reload path when assets cannot be served`() {
		assertThat(classifyUnservedAssets("app/src/main/java/com/example/Main.kt"))
			.isEqualTo(BuildRoute.CodeOnly)
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
