package org.appdevforall.cotg.quickbuild.domain.classify

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Negative sides of [ChangeClassifier]'s Gradle-config detection: files whose NAMES
 * look configuration-ish but whose paths say otherwise must not trip a full Gradle
 * invalidation - a spurious rebaseline costs the user a ~97 s proxy app rebuild.
 */
class ChangeClassifierEdgeTest {
	private val classifier = ChangeClassifier()

	private fun classify(vararg paths: String): BuildRoute = classifier.classify(ChangedFiles.Known(paths.map(::File).toSet()))

	@Test
	fun `a toml outside any gradle segment is not gradle config`() {
		// e.g. a Rust/Cargo file vendored under src: unsupported shape, honest fallback -
		// but NOT because it was mistaken for a version catalog.
		assertThat(classify("app/src/main/java/com/example/Cargo.toml"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED))
	}

	@Test
	fun `a wrapper-named properties file outside the wrapper dir is not gradle config`() {
		assertThat(classify("app/src/main/assets/gradle-wrapper.properties"))
			.isEqualTo(BuildRoute.AssetsOnly)
	}

	@Test
	fun `a properties file inside the wrapper dir with another name is not gradle config`() {
		assertThat(classify("gradle/wrapper/other.properties"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED))
	}

	@Test
	fun `a res-like path outside src is not a resource`() {
		// A stray res/ dir at the project root is not an Android source-set resource.
		assertThat(classify("res/values/strings.xml"))
			.isEqualTo(BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED))
	}

	@Test
	fun `a kotlin file in a package named res is code, not a resource`() {
		// `res` is a legal package name. Read as a resource, the route becomes ResourcesOnly:
		// aapt2 relinks, the cached dex is reused, nothing compiles, and the edit is silently
		// missing from the running app.
		assertThat(classify("app/src/main/java/com/example/res/Strings.kt"))
			.isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `a java file in a package named assets is code, not an asset`() {
		assertThat(classify("app/src/main/java/com/example/assets/Loader.java"))
			.isEqualTo(BuildRoute.CodeOnly)
	}

	@Test
	fun `namesResource is false for a code file in a package named res`() {
		// Same shape via the diagnostic-attribution helper: a kotlinc error in this package must
		// not be blamed on aapt2, and must not count toward the stuck-relink escalation.
		assertThat(ChangeClassifier.namesResource(File("app/src/main/java/com/example/res/Strings.kt")))
			.isFalse()
		assertThat(ChangeClassifier.namesResource(File("app/src/main/res/values/strings.xml")))
			.isTrue()
	}
}
